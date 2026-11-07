# Note search: OpenSearch infrastructure and adapter

Status: projection decoding, index mapping, safe query construction, the fixed-origin
mTLS adapter and private OpenSearch deployment are implemented and tested.
The Kafka consumer, canonical snapshot rebuild and Space-authorized user search API
are now deployed. GET /api/spaces/{spaceId}/search/notes is available through Gateway.
The search portion of stage 6 is implemented; graph projection remains separate work.

## Ownership and compatibility

Content remains the canonical owner of notes and revisions. Brain Query uses the existing,
unchanged content_note_v1 Protobuf contract, and never reads Content PostgreSQL.
This step adds a dependency on the existing internal event-contracts module only.
OpenSearch is an accepted architecture component and is deployed as private read-model storage.

## Implemented rules

- Only content-service NoteCreated/NoteUpdated/NoteRetired v1 events are accepted.
- Aggregate and payload note IDs must match; event, revision, note and Space IDs must be UUIDv7.
- Only PRIVATE ACTIVE/RETIRED revisions with positive signed-long-compatible versions are accepted.
- Retired documents retain their version and state, with title/body erased. Do not physically
  delete tombstones: older Kafka events must not resurrect retired notes.
- The index write uses the canonical note version and external. Equal/older versions must be rejected by OpenSearch, preventing equal-version
  conflicting snapshots from overwriting a document. The consumer verifies an
  existing version/document before acknowledging a duplicate conflict; unrelated conflicts
  must fail closed. Real OpenSearch integration tests cover duplicates, older revisions and equal-version conflicts.
- Every query includes Space, PRIVATE and ACTIVE filters in the query itself, covering counts
  as well as hits. Search input is a plain-text multi_match value, never query_string/DSL.
- Maximum query length is 200 code points; control characters are rejected. Pages contain
  1-50 hits with a bounded 1,000-result window. Returned source fields are noteId/title only.
- The strict index mapping uses keyword IDs/state and standard-analyzed title/Markdown.
  Replica counts, shard counts and resource budgets belong in environment deployment values.

The query builder receives a previously authorized Space ID. It does not perform authorization.
The HTTP boundary calls Space on every request and fails closed on denial/outage,
including before obtaining totals. See [note-search-api.md](note-search-api.md).
Standard analysis is an initial baseline; Korean morphological search is not implemented.

## Required work before usable search

1. Completed: pinned/licensed OpenSearch 3.9.0, private mTLS, non-demo certificates,
   separate reader/projector roles, actual Pod-replacement persistence and network checks.
2. Completed: separate Brain Query NOTE_SEARCH authorization contract/key, reader mTLS,
   API TLS, Gateway route and OpenAPI. Fresh authority checks protect counts and hits.
3. Completed: restricted content.note.v1/group ACLs, durable Inbox/queue, retry,
   post-projection acknowledgement, bounded metrics and payload-free trace logs.
   See [note-projection.md](note-projection.md) for runtime outage tests.
4. Completed: restricted Content-owned canonical snapshot API and local index rebuild,
   with concurrent Kafka tail replay, tombstones and durable checkpoint. No foreign DB
   access is used; see [note-search-rebuild.md](note-search-rebuild.md).
5. Test real OpenSearch ordering, duplicates, tombstones, indexing outages, index loss/rebuild,
   consumer restarts, revoked guests, authority outages, and common-origin HTTPS on Kubernetes.
6. Keep graph projection and Note-to-Concept work separate subsequent milestones.

## Dependency research (2026-10-07)

Official OpenSearch material identifies Apache-2.0 licensing. This permits commercial use
and redistribution under its terms, including required license/notice preservation. Review
bundled third-party notices for the selected artifact before introduction. The original upstream container image is now pinned and deployed. No external Java
client library is added; Brain Query uses the JDK HTTP client and existing Jackson.

- https://opensearch.org/downloads/
- https://opensearch.org/faq/
- https://docs.opensearch.org/latest/install-and-configure/install-opensearch/docker/

OpenSearch 3.9.0 is pinned by OCI digest in infra/dependencies/opensearch.json.
Docker examples contain demonstration configurations; production-equivalent TLS/security
must be explicitly configured for our local deployment.

Validation: [note-search-runtime-validation.txt](note-search-runtime-validation.txt),
13 tests passed (including 4 real OpenSearch integration tests) in the local Kubernetes
Java 25 validation Job. Separate runtime Jobs validate mTLS, role restrictions and persistence.
This earlier evidence verifies storage and the adapter. Current end-user API validation
is recorded in [note-search-api-validation.txt](note-search-api-validation.txt).

Deployment and reproducible operator commands: [opensearch.md](opensearch.md).
