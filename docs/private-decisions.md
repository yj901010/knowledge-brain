# Private decisions

Refresh `/graph` → **개인 결정 열기** → **새 결정 작성**. Record title,
context, selected outcome, rationale and considered alternatives. Select **초안**
or **결정 완료** manually. Completed decisions require nonblank outcome and
rationale. Returning to draft retains entered text; archival is an independent
lifecycle. Filter all/draft/completed records and active/archived records separately.
Open detail to edit, inspect immutable versions or confirm archival.

These are private personal records. They do not amend repository architectural
decisions, approve public contributions or verify Knowledge claims. The feature
follows existing PROJECT.md Content ownership, personal privacy and event delivery
decisions. No service boundaries, canonical ownership or consistency rules change.

## Persistence and API

Additive Content V10 owns decision and decision_revision in brain_content. All
domain IDs are UUIDv7; independent dcN versions, DRAFT/DECIDED status and ACTIVE/
RETIRED lifecycle. Snapshot/status, immutable audit, original creation intent and
standard Protobuf Outbox bytes commit in one local ACID transaction. Own revision
FK only; no cross-owner SQL, joins, FKs or writes. Existing immutable migrations
and schemas remain unchanged; Note, Question and Bookmark facts stay independent.

[private-decisions.yaml](../contracts/openapi/private-decisions.yaml) declares six
operations: GET/POST collection, GET/PUT/DELETE object, GET object/revisions.
Every request rechecks DECISION_READ/WRITE via existing dedicated Content→Space
capability and common guest/registered principal. PERSONAL/PRIVATE only; visible
members read, OWNER/ADMIN/EDITOR write. Existing NOTE/BOOKMARK/QUESTION actions
remain. No new external credential, dependency, dataset or AI capability.

Exact six-field input: title/context/outcome/rationale/alternatives/status. Title
is nonblank, at most200 Unicode codepoints/800 UTF-8 bytes, without newline/tab.
Each of the other four text fields is at most8192 UTF-8 bytes. Preserve CR/LF/tab,
reject other ISO controls/unpaired surrogates. DECIDED requires nonblank outcome
and rationale using Java isBlank. DRAFT may retain both or leave them empty. Four
independent text bounds keep worst-case escaped JSON within the existing256 KiB
transport limit and ten full revisions within Gateway's2 MiB response limit.
Plain text is escaped, not parsed or linked; there is no automatic remote fetch.

TLS, singleton secure browser identity, same-origin mutations, no-store, strict
duplicate/trailing JSON rejection, exact singleton known queries and empty GET/
DELETE bodies apply. Strong dcN tag required on edit/archive: missing428,
malformed400, stale412 before no-op. Unchanged current edits emit nothing. Known
archived GET/history200, edit404; archival204 including matching-version no-op.
Conflicts preserve the draft until explicit latest inspection/restart; archive
acknowledgment resets. The UI keeps unrelated Note and sibling drafts independent.

Create requires a random32-lowercase-hex Idempotency-Key. Same Space/key/validated
input replays original v1/dc1 after edits, archive, account claim or restart;
changed intent409. Concurrent same-key create yields one object/event. Controlled
Outbox failure rolls back creation/key/audit and edits. Lost create replies freeze
the same fields/key for retry; inspect the catalog before starting a fresh intent
after abandoning an uncertain request. Private data and keys stay in memory only,
never localStorage/sessionStorage. Account claim retains IDs/audit/guest actors;
subsequent edits record the registered actor.

Compact catalogs omit all four full text fields,20 default/100 maximum. Ascending
cursor binds Space/lifecycle/status ALL/DRAFT/DECIDED; live pages are not frozen
snapshots. History5 default/10 maximum, descending exclusive versions. Close,
Space change and authority loss discard late responses; catalog failures clear
stale rows and filter changes clear selection. Archive retains full history.

## Events and local validation

DecisionCreated/Updated/Retired use immutable content_decision_v1.proto and literal
content.decision.v1, Registry content/decision-events/1 with FULL validity and
FULL_TRANSITIVE compatibility. Full private revision and standard11 envelope
include actor/Space/HTTP trace. Existing Outbox→publication→content-outbox Debezium
connector→Kafka preserves slot/offsets. At-least-once consumers deduplicate stable
event IDs. Run-DecisionContractValidation.py verifies eight previous Content
schemas read only, with no mutation of their source or rules.

Only the existing Connect producer and read-only Content validator receive the
additional literal topic permissions. App projectors retain their original
streams. Integrated Decision graph/search/activity projections and explicit domain
links remain future increments through events rather than cross-owner SQL.

Provision-ContentCdc.py verifies own Outbox SELECT; Note and six Bookmark/Question/
Decision canonical SELECT denials; twelve other owner DB CONNECT denials and
one-table publication. Data/PVCs/topics/slots/offsets are preserved. Existing Java25
virtual threads, reviewed pinned dependencies, own pool/readiness, safe errors,
bounded read/change counters and trace→Outbox→Kafka headers are reused. Central
OpenTelemetry exporters/Prometheus/Loki/Tempo/Grafana remain platform work.

Build/import/install Space, Content and Gateway independently on both k3d nodes.
Backend: Run-BackendValidation.py --content-kafka --planning-kafka
--notification-kafka --activity-storage. Focused browser: Run-GraphWebValidation.py
--decisions-only. Full browser: --public, including two real Keycloak claims.
Evidence: [private-decisions-validation.txt](private-decisions-validation.txt).
