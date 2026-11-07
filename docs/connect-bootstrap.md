# Debezium/Kafka Connect: Identity and Space Outboxes

Space now has a second independent connector and event topic, `space.personal-space.v1`, with the same base/local partition, replication and retention settings as Identity. Five topics are provisioned in total. Connect writes both domain topics. The projected `/secrets` directory contains only Identity and Space CDC properties; FileConfigProvider permits that directory. Neither owning application's credential is given to Connect. Space's durable state-changing consumer and current run commands are documented in [space-provisioning.md](space-provisioning.md). The Identity checks below remain transport checks.

The owning-service path is Identity PostgreSQL transaction -> `public.outbox_event` -> Debezium PostgreSQL connector -> Outbox Event Router -> Kafka `identity.guest-identity.v1`. Application code does not publish to Kafka inside its database transaction. Existing MSA ownership and at-least-once semantics remain unchanged.

## Deployment and credentials

The `brain-connect` Helm chart uses the official Debezium 3.7.0.Final image, pinned by manifest digest. It runs distributed Connect workers with Kafka 4.3.1 compatibility. Base values use two workers, three Kafka replicas and min ISR two; local values use one worker/replica and min ISR one. The same topics, TLS, ACLs, connector and database publication remain present in both configurations.

Five topics are created explicitly: compacted `brain-connect-configs`, `brain-connect-offsets`, `brain-connect-status`, and the append-retained Identity and Space event topics (seven days). Configuration has one partition; offsets use 25 base/3 local partitions; the event topic uses 3 base/1 local partitions. Broker auto-creation remains disabled. Initial snapshots recover retained Outbox rows; pruning is not enabled. Snapshot/replay and consumer idempotency are required before introducing pruning.

The `brain-connect` Kafka user uses client-certificate authentication, reads/writes its three internal topics, and writes the Identity event topic. The separate `brain-cdc-validator` user can only read that event topic with the validation consumer-group prefix; writing is denied. Worker, producer and consumer clients validate broker hostnames against the cluster CA. The KafkaUser `ca.crt` is a **clients CA**, so validation trust must use `brain-kafka-cluster-ca-cert/ca.crt` instead.

`brain_identity_cdc` is a dedicated PostgreSQL LOGIN/REPLICATION role, with no superuser, DB/role creation, bypass-RLS or inherited application roles. It can connect to `brain_identity`, use `public`, and SELECT only `outbox_event`. It cannot SELECT guest credentials or connect to the other 12 owner databases. The Identity owner creates `identity_outbox`, containing only `public.outbox_event`; Connect never receives owner/admin credentials and cannot auto-create publications. REPLICATION is a PostgreSQL cluster-level privilege, so this is a trusted infrastructure credential, not a generic table-reader identity. Preserve restricted database connection grants and publication ownership when adding future connectors.

Bootstrap creates/reuses a random CDC password without printing it, configures each role using an ephemeral admin/owner Job, and stores a `password=...` file in the worker namespace. Connector JSON uses FileConfigProvider references. The provider permits `/secrets`, which projects only `identity.properties` and `space.properties`. Worker client configuration overrides are disabled. Secret rotation requires updating the role/Secret together and restarting affected connector tasks/workers; certificate rotation requires refreshing the validation namespace copy.

Connect REST is ClusterIP-only, port 8083, with no ingress/NodePort. An ingress NetworkPolicy permits only Connect peers and pods explicitly labeled `brain-connect-admin=true` in `brain-data`. Network access is the current management boundary; namespace pod/label/Secret permissions must remain restricted to trusted infrastructure operators. There is no application/public management route. Workers and Jobs do not mount a Kubernetes API token. Workers are non-root and drop capabilities. The image entrypoint writes its configuration, so its root filesystem is not marked read-only.

## Local run

Requires the Ready PostgreSQL migration, Kafka/Strimzi, Registry contract, and local context `k3d-knowledge-brain`. These helpers refuse other context names.

```powershell
python ./scripts/Provision-IdentityCdc.py
# Wait for the printed bootstrap JOB and inspect its logs before deploying Connect.
kubectl --kubeconfig .local/kubeconfig.yaml --server=https://127.0.0.1:6550 -n brain-data wait --for=condition=complete job/<BOOTSTRAP_JOB> --timeout=180s
kubectl --kubeconfig .local/kubeconfig.yaml --server=https://127.0.0.1:6550 -n brain-data logs job/<BOOTSTRAP_JOB>
helm upgrade --install brain-connect ./infra/charts/brain-connect --namespace brain-events --kubeconfig .local/kubeconfig.yaml --kube-apiserver=https://127.0.0.1:6550 -f ./infra/environments/local/connect-values.yaml --wait --timeout 5m
python ./scripts/Run-IdentityConnectorConfiguration.py
# Wait for and inspect the printed configuration JOB.
```

The idempotent configuration PUT preserves the connector name, replication slot `identity_outbox_v1`, topic prefix and publication. REST status may briefly return 404/409 before the distributed worker registers the task; the helper retries these states and requires both connector and task RUNNING. Do not delete/recreate the replication slot or reset offsets to fix an ordinary restart. Do not use base Kafka replica factors on the one-broker local cluster.

## Contract and failure semantics

`infra/connectors/identity-outbox.json` captures only `public.outbox_event`, uses `pgoutput`, takes an initial snapshot, and preserves `bytea` as bytes. A topic-name predicate applies the Outbox transformation only to Outbox data. Heartbeat, transaction metadata and schema-change output are disabled in this initial connector. BinaryDataConverter passes the complete stored Protobuf envelope unchanged; its JSON delegate is configured for future nonbinary metadata, not for domain values.

The aggregate ID is the Kafka key. Headers include stable event `id`, `trace_id`, and exact Registry `schema_group`, `schema_artifact`, `schema_version`. No serializer magic-byte frame is introduced. Outbox updates fail the connector (`fatal`); the router filters deletes. Never UPDATE an event to retry publication. Errors are not silently skipped. All state-changing downstream consumers must atomically persist their deduplication/inbox state with their own domain/projection changes and commit Kafka offsets afterward.

## Verification and operations

```powershell
python ./scripts/Provision-CdcValidationClient.py
python ./scripts/Run-BackendValidation.py --cdc
# Wait up to 600 seconds for the printed Job, then inspect its logs.
python ./scripts/Run-ConnectAccessValidation.py
# Wait for both printed access Jobs and inspect their logs.
```

The Kafka integration test runs inside Kubernetes with Java 25 and real owner DB/mTLS consumer credentials. It reads only Identity's own DB, compares every Kafka value byte-for-byte with its source Outbox, checks keys/schema/trace/event-ID headers, verifies that a fully rolled-back transaction does not appear during the observation window, reconsumes the records with stable IDs/content, and checks readonly topic-write rejection. Its consumer does not mutate a projection or implement a durable business inbox. `--identity` limits a repeat run to Identity and its contract dependencies; the full run checks all 13 services.

Restart validation temporarily scales the local worker to zero, commits a synthetic event through the existing owner-side DB integration test, restores one worker, and checks that all retained Outbox event IDs/bytes appear in Kafka, including the outage fixture. Existing committed offsets/slot/publication persist. This is local recovery evidence, not proof of arbitrary disaster recovery or exactly-once delivery. Successful synthetic fixtures remain as replay inputs.

Operational signals currently available are Connect connector/task state, structured Debezium logs with slot/LSN progress, Kafka internal offsets, Kubernetes probes/resources and propagated event trace IDs. A failed connector task can coexist with a Ready worker, so check task state explicitly. Before production rollout add the prescribed observability stack, alerts for failed tasks/consumer lag/retained WAL, PostgreSQL transport TLS, secret/certificate rotation automation, backups and HA validation. Monitor the inactive replication slot when workers are stopped; deleting source events or letting WAL be removed can invalidate recovery.

Identity transport evidence: [connect-runtime-validation.txt](connect-runtime-validation.txt). The subsequent Space implementation and evidence are in [space-provisioning.md](space-provisioning.md); browser principal flow remains subsequent work.

Primary references: [Debezium 3.7 PostgreSQL connector](https://debezium.io/documentation/reference/3.7/connectors/postgresql.html), [Outbox Event Router](https://debezium.io/documentation/reference/3.7/transformations/outbox-event-router.html), [official image entrypoint](https://github.com/debezium/container-images/blob/main/connect-base/3.7/docker-entrypoint.sh).
