# Identity event and transactional outbox foundation

This implements the first owning-service transaction, not the complete guest browser flow. AGENTS.md and PROJECT.md remain authoritative: Identity writes only `brain_identity`, Space creation belongs to Space, and event delivery will use Debezium/Kafka Connect. No cross-service queries, public guest endpoint, application Kafka producer, or guest Keycloak account is introduced.

## Contract

`contracts/events/src/main/protobuf/identity_guest_v1.proto` is the immutable source for Apicurio `identity/guest-identity-events`, version `1`. The artifact includes `EventEnvelopeV1` and `GuestIdentityCreatedPayloadV1`. Both domain IDs and event IDs are UUIDv7. The envelope carries event type/version, UTC occurrence time in epoch milliseconds, producer, aggregate, optional space/actor, nonzero W3C trace ID and typed Protobuf payload bytes. Space ID is absent until Space owns an actual space; the guest principal is the actor. Credentials and credential hashes never enter the event.

The planned Kafka value is the complete raw Protobuf envelope stored in the outbox `bytea` column. It is not an Apicurio serializer magic-byte frame. CDC must preserve these bytes and carry the exact schema group/artifact/version as headers; consumers select the matching contract and deduplicate by envelope `event_id`. Registry rules are `VALIDITY=FULL` and `COMPATIBILITY=FULL_TRANSITIVE`. Never reuse field numbers or silently replace artifact version 1. Breaking domain contracts require a new event version and a compatible rollout.

Compiler and runtime are both 4.36.2, pinned in the parent POM; this also overrides Spring Boot's older managed Protobuf runtime in consuming services. Generated Java is a build output, not committed source.

## Local transaction

`V1__guest_identity_outbox.sql` creates guest identities, the append-only-by-convention outbox, and an occurrence-time index. Flyway applies it using the Identity owner only. `GuestRegistrationStore.register` validates an already-derived SHA-256 credential hash and trace ID, generates IDs, constructs Protobuf, and inserts identity plus outbox within one local Spring transaction. Failure in either insertion rolls both back; no network publication happens during the transaction.

Runtime database initialization is opt-in using `brain.identity.persistence.enabled=true`, `brain.identity.database.url`, and `brain.identity.database.password` from the owning service's Secret. The current deployed baseline is not switched into a public registration flow. The initial data source opens PostgreSQL connections directly; a bounded connection pool must accompany the application rollout. Browser-token creation, expiry/rotation, common registered/guest principal mapping and private Space creation remain separate implementation work.

No outbox deletion policy is enabled yet. Do not prune events until CDC offsets, recovery/snapshot behavior and replay retention have been verified. Every future state-changing consumer must implement its own durable event-ID deduplication in the same transaction as the projection mutation.

## Verification

With JDK 25 and a working native Protobuf compiler:

```powershell
./mvnw.cmd verify
```

Ordinary builds run unit/boundary tests; the real PostgreSQL test requires `BRAIN_TEST_DATABASE_URL` and `BRAIN_TEST_DATABASE_PASSWORD`. It is intended for a disposable/local Identity database. It applies migrations, commits one synthetic identity/event, rejects a duplicate credential, and temporarily installs a test-only CHECK constraint to prove that an outbox failure rolls back the prior guest insertion. It removes the constraint in `finally`; successful synthetic rows remain as a CDC fixture. Do not point this test at production or a busy shared database.

For this Windows execution environment, native `protoc` failed with process error 623 and host port-forward connections were inaccessible. The reproducible local Kubernetes runner uses the normal unmodified Maven build, Linux native protoc, Java 25 and the actual local PostgreSQL service:

```powershell
python ./scripts/Run-BackendValidation.py
# Use the printed JOB name:
kubectl --kubeconfig .local/kubeconfig.yaml --server=https://127.0.0.1:6550 -n brain-data wait --for=condition=complete job/<JOB> --timeout=600s
kubectl --kubeconfig .local/kubeconfig.yaml --server=https://127.0.0.1:6550 -n brain-data logs job/<JOB>
```

The runner checks the local context, copies only build/source inputs into a ConfigMap, mounts no kube API token, and references the existing owner Secret without printing it. The Maven image is pinned by digest. Jobs and owned source ConfigMaps expire after 24 hours. It downloads Maven dependencies; it does not bypass code generation or use committed generated classes.

Register or validate the exact immutable contract with the existing local publisher/reader credentials:

```powershell
python ./scripts/Run-IdentityContractValidation.py
# Inspect the printed Job with kubectl wait and logs, as above.
```

The helper checks existing version-1 content before reusing it and verifies both Registry rules and a readonly content read. It does not modify an existing version to make it match source. Remove its source ConfigMap after the Job expires if it is no longer needed.

## Next deployment boundary

The initial foundation was subsequently extended by [Connect deployment and transport validation](connect-bootstrap.md). That step introduces the Connect workers, explicit internal/event topics and mTLS ACLs, a replication login restricted to the Identity database/outbox, an owner-created publication, and the PostgreSQL Outbox Event Router with binary passthrough. Committed bytes, rollback exclusion and restart/replay are verified in the transport step. Durable idempotent consumption and Guest Personal Space/browser-token work remain the next domain boundary, using the existing service boundaries.
