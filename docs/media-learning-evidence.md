# File learning evidence

Open a private Personal Roadmap and choose **파일 학습 근거 열기**. Select an active
learning node, a completed file from the same Personal Space and an optional memo.
Upload new files first through a Note's existing attachment panel; the file library
is shared within the Space, while Note attachments and Learning evidence are
independent relationships. Removing either relationship does not delete Media.
The file picker pages the actual Media catalog 20 at a time, validates every page,
excludes PENDING files and retains pagination even when a page has no completed files.

Learning pins the Media ID, version, filename, MIME, byte size and SHA-256 alongside
the original node ID/title and private memo. It stores no file bytes, MinIO object
key/version, upload policy or signed URL. Media owns metadata and the immutable
sealed object version; MinIO owns bytes. All learning records remain PRIVATE.
There is no automatic progress, completion, study duration, public publication,
verification or ranking. Manual study tracking and sibling drafts stay independent.

Use **현재 파일 확인** for current authorized metadata and **근거 파일 다운로드** to
retrieve the original file. Downloads obtain a fresh same-origin Media capability,
restrict the returned URL to the configured HTTPS storage origin, omit cookies and
referrers, reject redirects and verify byte size and SHA-256 before offering a Blob
download. Signed credentials and object URLs are not persisted. Failed, corrupted,
cancelled, late or revoked-session results cannot restore private state or start a
download. Files are downloaded rather than rendered as HTML or inline previews.

## Ownership and compatibility

The new typed collection is
`/api/spaces/{spaceId}/roadmaps/{roadmapId}/media-evidence`.
Learning owns the existing evidence/revision tables. V5 adds a MEDIA discriminator,
nullable external media ID/version columns, an exact source-shape constraint and
an active node/media/version unique index. NOTE and CONCEPT rows retain their IDs,
JSON revisions, request fingerprints and original committed Outbox bytes. A real
PostgreSQL V4-to-V5 migration test verifies both variants, original-key replay and
wrong-kind ID isolation. No cross-service SQL, join, FK, write or distributed
transaction is introduced, and Note/Concept JSON and event v1 bytes remain unchanged.

Every operation freshly checks destination ROADMAP_READ/WRITE through Space.
Before a new link, Learning calls Media's narrow HTTPS owner read
`/api/spaces/{spaceId}/media/{mediaId}/learning-reference` with the same common
guest/registered Cookie and trace context. Media independently checks read access
and AVAILABLE state. Learning checks echoed Space, principal, source ID, expected
version and bounded metadata. Foreign or unavailable source is 404; PENDING/current
version mismatch is 409; malformed owner metadata/unavailable TLS owner check is
503. Actor equality detects an identity transition between the two authorizations.
The Gateway does not expose this owner-only route; Media ingress permits Learning.
No service-wide snapshot credential or cross-owner DB privilege is added.

## Writes, audit and events

POST accepts exactly `{nodeId, mediaId, mediaVersion, memo}` and one lowercase
32-hex `Idempotency-Key`. Keys are unique across all evidence variants per Roadmap.
Same key and original payload returns the CURRENT record with 200, even after
retirement, without another owner check/event. Another payload/kind is 409. A new
active pin returns 201; another key for the same active pin is 409. UUIDv7 root,
node and source IDs are required; Learning generates evidence/revision/event IDs.

An existing root lock serializes evidence creation with node/root retirement.
New links require an active root/node. Owner validation and the local commit are
not a distributed transaction: the stored pin is historical. Reads and immutable
history remain auditable after node/root retirement; edits and soft retirement
require an active root. PUT accepts only `{memo}` and never changes source metadata.
DELETE has no body. Independent strong `"meN"` If-Match is mandatory for both;
missing/wrong/stale values return 428/400/412. No-op edits still check version but
write nothing. Explicit UI latest inspection/restart resolves conflicts and resets
retirement confirmation. Raw memo limit is 8192 UTF-8 bytes; strict JSON rejects
duplicate, unknown and trailing fields, invalid integers and oversized bodies.
Catalog default20/max100 is ascending UUID keyset; history default5/max10 is
descending version. Responses are no-store and mutations require same origin.

Every real change atomically commits the relationship, full immutable revision and
Protobuf Outbox in Learning. The independent `learning.media-evidence.v1` topic and
`learning/media-evidence-events` Registry v1 carry
`MediaLearningEvidenceLinked/Updated/Retired` with full pin/memo/state, stable
event/revision IDs and actor/Space/trace/schema metadata. Register FULL validity and
FULL_TRANSITIVE compatibility and reconcile exact-topic Connect write/validator
read ACLs before writing the first event. The existing Outbox publication, CDC
slot, broker offsets and canonical-table/other-owner DB restrictions are preserved.

Counters `brain.learning.media.evidence` (bounded operation labels) and
`brain.media.learning.references`, database/storage readiness, HTTP metrics and
HTTP-to-Outbox-to-Kafka trace propagation are included. The central telemetry stack
and exporter deployment remain separate platform work.

## Local validation

Build/import Media, Learning and Gateway on both k3d nodes using the existing
scripts. `Install-LocalRoadmaps.py` accepts `--media-only`, `--learning-only` and
`--gateway` with `--helm`. Run the tracking contract publisher, Learning CDC
privilege verifier, `Run-BackendValidation.py --learning --learning-kafka`, full
backend reactor, focused `Run-GraphWebValidation.py --media-learning-evidence-only`
and full `--public --no-images`. The focused suite exercises actual sealed MinIO
uploads/downloads, pending/foreign denial, exact pins, typed isolation, independent
Note attachments, 5+3 history, real file/evidence paging, lost committed response,
edit/retire conflicts, SHA-256 rejection, sibling drafts, late reads, session
revocation and 390px layout. The full public workflow retains file evidence IDs,
snapshots and sealed Media across two actual guest-to-Keycloak Space claims, then
edits the same records under the registered identity. See the adjacent validation
proof for successful job IDs and totals.

No new dependency, dataset or license was added. Optional StudySession association,
Activity timeline projection, public/group Roadmap learning and ratings/ranking
remain follow-up. Visual redesign stays deferred in favor of functionality.

Optional StudySession association is now available through a separate typed relationship; see [evidence-study-sessions.md](evidence-study-sessions.md). Existing evidence/session JSON and immutable event v1 contracts remain unchanged.
