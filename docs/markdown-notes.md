# Private Markdown Notes

Implemented milestone: Content-owned Markdown Note create/read/list/update/retire,
immutable revision snapshots, strong version preconditions, and PostgreSQL
transactional Outbox -> Debezium -> Kafka. This is the first note capability;
Wiki Link/backlink parsing is implemented in the subsequent milestone documented
in [wiki-links.md](wiki-links.md). Properties/tags, attachments, projections and
the web editor remain later milestones. Markdown itself stays unchanged.

## Ownership and authorization

Content uses only its `brain_content` database and owning credential. Note,
revision and event IDs are UUIDv7. Space and actor references are immutable IDs,
without external foreign keys or cross-service queries. New notes are PRIVATE;
the migration rejects any PUBLIC note visibility. Every read/write, including
history after retirement, requires authoritative Space authorization.

Content calls the fixed HTTPS Space endpoint `/internal/space/content-authorization`
using a separate Content-to-Space service key and the guest credential. Space
authenticates through Identity, verifies ACTIVE Space/membership, and decides
NOTE_READ/NOTE_WRITE. Initial scope is PRIVATE PERSONAL Space: members may read;
OWNER/ADMIN/EDITOR may write. Other Space kinds fail closed until their policies
and product flows are implemented. There is no positive permission cache. Content
receives verified principal ID from Space and ignores caller principal/user headers.
Content has neither Identity introspection key nor any other service DB credential.

The synchronous chain is Gateway -> Content -> Space -> Identity, with fixed
HTTPS origins, verified certificates, disabled redirects and bounded client
timeouts (connect 3s, authority read 5s; Gateway complete-response 10s). This is an
authoritative check, not a distributed write transaction or joined read view.
Transport/authority errors deny access with 502/503/504; no offline authorization
fallback is used. Registered identities and group/public notes remain unimplemented.

## API

OpenAPI: `contracts/openapi/content-notes.yaml`. The common Gateway contract also
includes the Note paths; the private Space operation is excluded from Gateway.

| Method/path | Behavior |
| --- | --- |
| POST `/api/spaces/{spaceId}/notes` | JSON title/markdown; 201 Note, Location and ETag |
| GET `/api/spaces/{spaceId}/notes` | Active summaries, UUID keyset after cursor, limit 1..100 (default 20) |
| GET `/api/spaces/{spaceId}/notes/{noteId}` | Active Note snapshot and ETag |
| PUT `/api/spaces/{spaceId}/notes/{noteId}` | JSON replacement title/markdown; requires If-Match |
| DELETE `/api/spaces/{spaceId}/notes/{noteId}` | Requires If-Match; 204 after retirement |
| GET `/api/spaces/{spaceId}/notes/{noteId}/revisions` | Descending versions, exclusive before cursor, limit 1..10 (default 5) |

Use `__Host-brain_guest` browser cookie and HTTPS. Mutations require the configured
exact Origin and absent/same-origin Sec-Fetch-Site. POST/PUT require JSON containing
exactly two strings: title and markdown. Title is nonblank, up to 200 Unicode code
points; Markdown up to 32768 UTF-8 bytes; neither may contain NUL. JSON request body
is bounded at 256 KiB to allow escaped Markdown. Guest-session body limit stays
1 KiB. Content response limit at Gateway is 2 MiB for bounded revision pages;
Identity/Space remain 64 KiB. Only documented collection/revision pagination query
keys are forwarded; no arbitrary backend URL or unknown query forwarding.

GET returns ETag such as `"1"`; send exactly that value as If-Match when updating
or deleting. Missing If-Match returns 428, weak/wildcard/multiple/invalid values
400, stale version 412. Reload on conflict and consciously reconcile text before
saving. A row lock serializes competing writers; only one request using the same
version succeeds. Neither the rejected request nor failed Outbox writes produce
extra revisions. Mutations always create a new version, including equal-text saves.

Deletion changes state to RETIRED and records a new revision/event. Active reads
return 404 and list excludes the Note. Authorized history still retains all text
and actors. Unknown/inaccessible Space and wrong-space Note IDs return 404.
Responses prohibit caching; permission-filtered summaries exclude Markdown.
Markdown is stored literally, including HTML-like text, without rendering or
parsing. A future frontend must use a safe Markdown rendering policy before
displaying HTML. No external Markdown library, AI parser or dataset was added.

## Transaction and events

Flyway `V1__markdown_note_revisions_outbox.sql` creates only Content-owned tables:
note, note_revision (same-service FK and unique note/version), outbox_event.
Business mutation, immutable revision snapshot and complete Protobuf envelope
commit together. Outbox errors roll all three back. History is not modified by
ordinary API writes. List order is ascending UUIDv7, not last-updated order.

`content_note_v1.proto` keeps stable envelope fields 1..11. Event types are
NoteCreated, NoteUpdated and NoteRetired. Payload carries note/revision ID, version,
state, PRIVATE visibility, title and Markdown. Envelope includes Space, verified
actor, stable event ID and W3C trace ID. Apicurio `content/note-events` v1 uses FULL
validity and FULL_TRANSITIVE compatibility. PRIVATE payloads require restricted
topic ACLs and permission-filtered future projections; the topic is not public.

Content publishes no direct Kafka messages. `brain_content_cdc` can SELECT only
its outbox, cannot read Note rows and cannot connect to the other 12 owner DBs.
Connect mounts only the scoped CDC secret, publication content_outbox, slot
content_outbox_v1, initial snapshot restricted to outbox. The precreated topic
content.note.v1 uses stable Note key; local 1 partition/RF1 vs base 3/RF3. Connect
has Write/Describe only for this domain topic. Validator has Read/Describe and a
dedicated group prefix; application Content has no Kafka credential.

Delivery is at least once. Future state-changing projectors must durably deduplicate
event_id and apply per-Note versions; they must not treat this transport test as an
implemented Activity/Search/Graph projector. Rebuild requires canonical snapshots
plus events where appropriate; the local topic retains seven days, not permanent
history. Canonical revision snapshots and Outbox records are retained here.

## Local bootstrap, deploy and validation

All Kubernetes helpers guard the local k3d-knowledge-brain context and use the
repository kubeconfig with API server https://127.0.0.1:6550. Existing guest
credentials, Spaces and Notes remain intact during these steps.

1. Run Provision-GuestApiSecrets.py for own Content TLS/DB and separate
   Content-to-Space key; CA private key stays in operator namespace.
2. Run Run-BackendValidation.py --content for migration and DB/API tests.
3. Run Provision-ContentCdc.py and wait its bootstrap Job; run
   Run-ContentContractValidation.py and wait its registration Job.
4. Upgrade brain-connect with base chart + local connect values, then wait Ready
   and the content topic/user Ready. Run Run-ContentConnectorConfiguration.py,
   verify connector/task RUNNING, then Provision-ContentValidationClient.py.
5. Build/import content, space and gateway with the existing Build/Import-LocalSpaceImage.py
   --service argument. Wait all per-node import Jobs. Helm deploy each service
   with base + local service values and its actual immutable local image tag;
   placeholder local-dev is not a built image. Production browser Origin and
   registry image repository/tag must be configured explicitly.
6. Run Run-BackendValidation.py --cdc --space-kafka --content-kafka for the full
   real PostgreSQL/Kafka suite; Run-NotesApiValidation.py for common-origin HTTPS.
   Its --restart mode pauses at a marker to allow controlled authority outages
   and Content restart; keep replicas restored after validation.

Content mutation counters are brain.content.note.changes by created/updated/retired;
pool metrics and contentDatabase readiness check its owning DB. Gateway structured
logs correlate status/service/trace only, without cookies or note body. Traceparent
continues through Content -> Space -> Identity and into committed Note envelopes.
Full prescribed observability rollout remains a separate platform milestone.

To access from the PC, keep Start-LocalGateway.ps1 port forwarding running. Gateway
Pod replacement can end that process; rerun it using the existing process-only
ExecutionPolicy Bypass invocation. This milestone was verified inside Kubernetes;
host browser access and a frontend editor are not asserted by the runtime checks.

Actual evidence: `docs/markdown-notes-runtime-validation.txt`.

Graph UI editing and paged revision history delivery: [note-editor.md](note-editor.md).

## Canonical collection UI

The Personal Graph now provides a full paged active Note collection separately from
its bounded graph window. See [note-library-ui.md](note-library-ui.md).

Explicit version-protected Note retirement UI: [note-retirement-ui.md](note-retirement-ui.md).
