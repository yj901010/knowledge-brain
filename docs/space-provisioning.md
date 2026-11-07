# Guest Personal Space provisioning

Identity transaction -> Identity Outbox -> Debezium -> `identity.guest-identity.v1` -> Space transaction (Inbox, PRIVATE PERSONAL Space, OWNER membership, Space Outbox) -> Debezium -> `space.personal-space.v1`. Space reads only its owned PostgreSQL database. Persistent IDs are UUIDv7; Kafka offsets are committed after the database transaction.

Inbox uniqueness `(consumer_name,event_id)` and SHA-256 of exact input bytes make redelivery durable across restarts. Changed content with the same event ID fails closed. A unique Personal Space owner index prevents duplication from different event IDs. Database failure rolls back all four mutations. The consumer validates producer, version, schema headers, UUIDv7, key/principal/actor and trace consistency; failures stop consumption and make readiness DOWN without acknowledging the failing record. Structured trace/event/Space logs and Micrometer counters accompany the bounded Hikari pool and virtual-thread consumer.

Space's restricted `brain_space_cdc` role reads only its Outbox; publication `space_outbox` and slot `space_outbox_v1` are independent of Identity. Registry artifact `space/personal-space-events` version 1 has FULL validity and FULL_TRANSITIVE compatibility, content SHA-256 `36398d424891f711ef3e2da6655dfb9ddc1d2309eddfd8a745d1ededf583312b`. Raw Protobuf, keys and schema/trace headers are preserved by CDC.

Runtime Kafka user `brain-space` reads Identity and its own group prefix only. Separate validation credentials read both topics and validation groups. Connect alone writes the two output topics. Applications receive only their own DB credentials and scoped mTLS credentials; broker trust uses the cluster CA. Browser sessions and authenticated Space reads are now described in [guest-session-api.md](guest-session-api.md). Other domain routes and registered-user claiming remain subsequent work.

## Local run

Local Space uses Recreate deployment strategy: its single Kafka partition can be assigned to only one consumer, so a surge replica cannot become ready while the old replica retains assignment. Base replicas/partitions support rolling updates. This is environment configuration, not a separate service architecture.

Use the existing platform and `k3d-knowledge-brain` context. Helpers refuse other contexts. Require each printed bootstrap/configuration Job to complete successfully before continuing.

```powershell
python ./scripts/Run-BackendValidation.py --space
python ./scripts/Provision-SpaceCdc.py
python ./scripts/Run-SpaceContractValidation.py
helm upgrade --install brain-connect ./infra/charts/brain-connect --namespace brain-events --kubeconfig .local/kubeconfig.yaml --kube-apiserver=https://127.0.0.1:6550 -f ./infra/environments/local/connect-values.yaml --wait --timeout 5m
python ./scripts/Run-SpaceConnectorConfiguration.py
python ./scripts/Provision-SpaceRuntimeSecrets.py
python ./scripts/Build-LocalSpaceImage.py
# Require the printed build Pod to be Ready.
python ./scripts/Import-LocalSpaceImage.py
# Require ALL printed import Jobs to complete; substitute the printed local image tag.
helm upgrade --install space-service ./infra/charts/brain-service --namespace brain-apps --kubeconfig .local/kubeconfig.yaml --kube-apiserver=https://127.0.0.1:6550 -f ./infra/services/space-values.yaml -f ./infra/environments/local/space-values.yaml --set-string image.tag=<PRINTED_LOCAL_TAG> --wait --timeout 3m
python ./scripts/Run-BackendValidation.py --cdc --space-kafka
```

Jib builds Java 25 images inside Kubernetes without host Docker. Temporary local import Jobs verify the archive checksum and use only the local containerd socket on each node; this socket access is for trusted local operators. The application is non-root, drops capabilities and has a read-only root filesystem. The artifact Pod expires after 15 minutes; remove its Pod, Service, ConfigMap and NetworkPolicy after import. Production still requires explicitly configured GHCR images and GitHub Actions/Argo CD; this helper is local delivery, not production CD.

The Kafka test waits for the deployed application's committed Inbox, verifies PRIVATE/OWNER and other-principal denial, reopens a validation group without offset acknowledgement, and repeats processing with a fresh Store. It checks one Outbox per Space and byte-exact subsequent Kafka events. SQL queries are Space-owned only. This models the DB-commit/offset-commit crash window, not exactly-once delivery. Full OpenTelemetry/observability deployment, PostgreSQL TLS, rotation, backups and HA remain platform work.

Evidence: [space-runtime-validation.txt](space-runtime-validation.txt).
