# Live private Note search projection

Brain Query now consumes content.note.v1 through its own read-only Kafka identity.
Actual flow: common-origin HTTPS Content writes -> local ACID Note/revision/Outbox ->
Debezium -> raw Protobuf Kafka events -> Brain Query durable Inbox/queue -> OpenSearch.
Content remains canonical; no Content database, cross-service join or FK is accessed.

## Reliability

Brain Query owns brain_brainquery and its password. Migration V1 creates
note_search_inbox (stable event ID + SHA-256) and note_search_snapshot (latest derived
snapshot, version and projected version). Both receipt and queue advancement occur in
one local transaction. Duplicate event IDs with different bytes, immutable Space
changes and conflicting same-version sources are rejected with transaction rollback.
Concurrent consumers lock the derived note row; the highest revision wins.

The OpenSearch adapter applies strict external versions, retaining text-free RETIRED
tombstones in the index. An exact duplicate is acknowledged only after comparing the
stored version/source; older revisions are superseded. Applied state is recorded in
Brain Query's database before Kafka offset commit. Any crash between these steps is
safe to replay. Pending snapshots are drained after startup and while polling.

On storage, database or broker failure, the consumer closes its client and retries
with a three-second backoff. The failing offset remains uncommitted. Readiness goes
down during failures; malformed/conflicting events remain blocked rather than being
silently skipped or copied to an unrestricted dead-letter topic. Alert on repeated
brain.search.projection.failures; operator investigation may be required. Counters
use bounded outcome/failure tags. Acknowledgement logs contain event/trace IDs, never
private note titles, Markdown, cookies or certificate material. Transport key, event
ID, schema headers and trace IDs are validated against the unchanged Protobuf contract.

Expired/truncated committed Kafka offsets, or a new group whose retained beginning is
already greater than zero, require canonical rebuild. auto.offset.reset=none prevents
silent skipping. Unit tests cover the offset decision; no seven-day destructive
retention test was run. Kafka retains only seven days. Canonical Content snapshot
rebuild is now implemented; see [note-search-rebuild.md](note-search-rebuild.md).
The recovery operator coordinates snapshots, concurrent tail replay and checkpoints.
Projection-DB backup restoration reconciliation remains a separate recovery audit.
Do not reset group offsets without the canonical recovery procedure.

The service exposes only protected health probes over TLS. Joined idle replicas are
healthy even when Kafka has fewer partitions than consumers; multi-replica writes are
safe through the database lock and OpenSearch external version checks. The consumer itself grants no end-user access. The separate enabled HTTP boundary
now performs fresh Space authorization; see [note-search-api.md](note-search-api.md).

## Deployment

Release: brain-query-service, namespace brain-apps. It is a separate Java 25 container,
with virtual threads, own database credentials and its own Kafka group
brain-query-note-search-v1. Kafka ACLs allow Read/Describe on content.note.v1 and the
brain-query-note-search- group prefix only; no producer, wildcard topic or foreign DB
credential is granted. Broker CA is separate from Strimzi's client CA and TLS hostnames
are verified. It mounts only the scoped OpenSearch projector certificate, not admin
or reader credentials. The reader credential is mounted only by the local verifier.

The local Helm values use one replica and 256 MiB request/768 MiB limit. Production
may increase replicas and Kafka partitions independently. API TLS and NetworkPolicy
follow the existing restricted app chart. Service boundaries and event contracts are
unchanged. All libraries (JDBC/Hikari/Flyway/Kafka/Jib) reuse reviewed versions.

```powershell
python .\scripts\Build-LocalSpaceImage.py --service brain-query
python .\scripts\Import-LocalSpaceImage.py --service brain-query
# Wait for both printed node import Jobs to become Complete.
python .\scripts\Install-LocalNoteProjector.py --helm 'C:\path\to\helm.exe'
python .\scripts\Run-BackendValidation.py --brain-query --search-storage
python .\scripts\Run-NoteProjectionValidation.py
```

The common build/import tools now explicitly accept brain-query. They do not build,
merge or redeploy other service artifacts. All operator helpers restrict the context
to k3d-knowledge-brain; no credentials are printed or written to local files.

`Run-NoteProjectionValidation.py --restart` is a coordinated operator failure test.
After PROJECTOR_OUTAGE_READY, actually stop Brain Query, mark /tmp/projector.down
in the verifier Pod, wait PROJECTOR_OUTAGE_WRITE_DONE, replace the consumer Pod,
wait a different UID Ready and mark /tmp/projector.up. Repeat for OpenSearch using
SEARCH_OUTAGE_READY, /tmp/search.down, SEARCH_OUTAGE_WRITE_DONE and /tmp/search.up.
Restore both workload replica counts to one even if verification fails. The verifier
fails on missing handshakes instead of claiming recovery. No PVC is deleted.

## Verified scope and remaining work

Runtime validation creates private notes through the existing HTTPS Gateway, observes
real CDC/Kafka projection, tests update/Space-filtered hits, writes while the consumer
is stopped, writes while OpenSearch is stopped, verifies readiness failure/retry and
checks NoteRetired removal after recovery. Test credentials remain in process memory.
These are internal projection checks, not end-user search authorization tests.

Canonical snapshot/rebuild is complete. Implemented: fresh Space read-authorization for
Brain Query, the search REST/OpenAPI contract and the Gateway route. Registered-user
claiming, graph projection and Note-to-Concept remain separate planned work.

Evidence: [note-projection-runtime-validation.txt](note-projection-runtime-validation.txt).
Storage security: [opensearch.md](opensearch.md).
