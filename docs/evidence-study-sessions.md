# Evidence linked to study records

Open a private Roadmap, choose a Note, Concept or file evidence record, and select
**학습 기록 연결 열기**. Choose **연결할 학습 기록 선택**, select a record from the
same learning node and save explicitly. Record past study first using the existing
manual study panel. Session choices page 20 at a time and show only the matching
node; paging continues even if an entire source page belongs to other nodes.
Multiple evidence records can point to one session, and each evidence has at most
one current session association. This optional relationship changes neither the
evidence/source metadata nor session times, progress, completion or public knowledge.

The association pins the selected session ID, version, original node title, UTC
start/end and duration. Later session edits, moving it to another node or retirement
do not rewrite that pin. **현재 학습 기록 확인** explicitly reads the current session
to indicate a changed version/moved node or unavailable source. Selecting a current
version and saving explicitly replaces the pin. **학습 기록 연결 해제** requires a
fresh confirmation, retains full immutable history and does not delete the session.
The independent association history supports five-row descending version pages.
All data remains PRIVATE and is stored server-side; drafts exist only in component
memory. Other Note/Roadmap/evidence drafts and manual study tracking are independent.

## Ownership and compatibility

Both Evidence and StudySession are already Learning-owned facts in PROJECT.md.
Migration V6 adds `evidence_study_session` and its revision table, with local-only
FKs to Learning evidence and sessions. Existing tables, IDs, JSON snapshots, request
fingerprints and Protobuf v1 bytes are unchanged. A real V5-to-V6 migration test
verifies existing Note/Concept evidence, study records, original retries and Outbox
bytes before creating an independent link. There are no cross-service DB reads,
writes, joins or FKs, no new service, dependency or distributed transaction.

The typed resource is `{evidenceResource}/study-session`, where evidenceResource is
`/api/spaces/{spaceId}/roadmaps/{roadmapId}/evidence/{evidenceId}`,
`.../concept-evidence/{evidenceId}` or `.../media-evidence/{evidenceId}`.
Each supports GET, PUT and GET `/revisions`; other methods/kinds are not proxied.
Wrong-kind evidence IDs never resolve. Every operation freshly authorizes current
private PERSONAL ROADMAP_READ/WRITE through Space using the common guest/registered
principal. Same-origin is required for writes; responses are no-store. Real Keycloak
Space claim retains all association IDs/pins without copying domain objects.

GET returns a virtual UNLINKED version0 (`linkId`/timestamps/session pin all null)
until a real change occurs. Reads, empty history and an empty unlink never create
rows, revisions or events. A first real write generates UUIDv7 link/revision/event
IDs, and the link ID remains stable across unlink/relink. The strong ETag is
`"esN"`, independent of `evN`, `ceN`, `meN`, `ssN`, `prN` and `rmN`.

PUT accepts exactly `{sessionId, sessionVersion}`: both null to unlink, or an active
UUIDv7 session plus its expected positive version. JSON is strict: duplicate,
unknown, trailing fields, invalid/non-integral/out-of-range numbers or body above
4096 bytes are rejected. Strong If-Match is required (428 missing, 400 malformed,
412 stale). Empty bodies and query parameters are rejected at the Gateway.
History accepts singleton `before` and `limit` only, default5/max10.

## Local consistency and audit

The existing Roadmap row lock serializes association writes with graph, evidence
and session changes. New/re-pinned associations require an active root, evidence,
node and session in the same Space/Roadmap/node, and an exact current session version.
A mismatched node or changed target version returns409; foreign or unavailable
references return404. Unlink can clean up a relationship after evidence/node/session
retirement while the root is active. Retired roots reject every write, including
no-ops, but known typed association reads/history remain auditable. Exact existing
session ID/version is a no-op after the esN check, even if the source subsequently
changed or retired; it emits nothing and never silently advances the pin.

Relationship, full immutable revision and Outbox commit atomically in one Learning
transaction. No Idempotency-Key is required for this fixed conditional PUT resource.
After a lost response the previous esN becomes stale; the UI retains its selection,
requires explicit latest inspection and an explicit restart before a new save.
No blind retry overwrites another writer. A 412 resets unlink confirmation. Paging,
selection, close/switch and actual session revocation discard stale private responses.

The independent `learning.evidence-session.v1` topic and Registry
`learning/evidence-session-events` v1 carry `EvidenceStudySessionChanged` events with
full association state/pins, revision ID, stable event ID and actor/Space/trace/schema
metadata. UNLINKED payloads have empty session strings and zero numeric source fields.
Existing wire contracts remain immutable. Apply exact-topic Connect write/validator
read ACLs and reconcile Ready before writing events; publish FULL validity and
FULL_TRANSITIVE compatibility. Preserve the Learning publication, slot and offsets.
CDC now proves denial of all twelve canonical tables and all twelve other-owner DBs,
and may SELECT only the Outbox. No database, PVC, slot, offset or user data is reset.

Counter `brain.learning.evidence.sessions` has bounded read/write/history operations.
Existing readiness, HTTP metrics and HTTP-to-Outbox-to-Kafka trace propagation apply.
Central telemetry exporter/stack deployment is still a separate platform task.

## Local verification

Build/import/deploy Learning and Gateway on both k3d nodes using the existing local
scripts. Run `Run-BackendValidation.py --learning --learning-kafka`, the full reactor,
Registry publisher, CDC verifier, focused `Run-GraphWebValidation.py
--evidence-sessions-only` and full `--public --no-images`. Focused tests use real
Note/Concept/sealed Media sources and canonical sessions, all typed links, default
read/no-op, pins after edit/retirement, 20-row same-node picker, 5+3 history, lost
committed response, real esN conflicts, sibling drafts, late/malformed reads,
revocation and 390px layout. The public suite preserves all three links across two
actual Keycloak registrations and edits/unlinks them under registered authority.
Successful job IDs and totals are recorded in the adjacent validation proof.

This completes optional study-record associations for private Learning evidence.
Activity timeline projections, public/group Roadmaps, ratings/ranking, Planning,
Collaboration, GitHub integration and visual redesign remain separate next work.
Existing reviewed dependencies, pinned containers and licensed components are reused.
