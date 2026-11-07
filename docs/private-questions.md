# Private questions and direct answers

Refresh `/graph` → **개인 질문 열기** → **새 질문 작성**. Record a title,
background/context and your own answer. Select **미해결** or **해결** explicitly;
resolution requires a nonblank answer. An open question may keep a draft answer.
Reopening retains the entered answer unless the user explicitly changes it. Filter
the catalog by all/open/answered questions and active/archived lifecycle separately.
Self-reported resolution is not Knowledge verification or a public contribution.

Open a record to edit, compare a concurrent update, inspect immutable versions or
confirm archival. Archived detail and history remain discoverable. Plain text is
escaped and whitespace preserved; no answer generation, parsing, automatic links,
remote fetch, publishing or new dependencies are introduced. Existing Note and
other domain drafts and canonical facts remain independent.

## Ownership, persistence and authorization

PROJECT.md already assigns Questions to Content. Additive Content V9 owns question
and question_revision in brain_content; UUIDv7 IDs, independent versions, ACTIVE/
RETIRED lifecycle and OPEN/ANSWERED status. Status, snapshot and immutable audit
revision commit with the standard Protobuf Outbox bytes in one local ACID
transaction. Own revision FK only; no other owner SQL/joins/FKs/writes. Existing
immutable migrations and event contracts are unchanged.

[private-questions.yaml](../contracts/openapi/private-questions.yaml) documents six
operations: collection GET/POST, object GET/PUT/DELETE and GET object/revisions.
Every request rechecks current QUESTION_READ/WRITE using the existing dedicated
Content→Space service capability and common guest/registered principal. Only
PERSONAL/PRIVATE Spaces; visible members read, OWNER/ADMIN/EDITOR write. Existing
NOTE and BOOKMARK permissions remain. Internal capability endpoints are never
Gateway routes, and no new purpose credential or external account is required.

Input has exactly title, context, answer, status. Title is nonblank, at most200
Unicode codepoints/800 UTF-8 bytes, without newline/tab/control/unpaired surrogate.
Context and answer are each at most16384 UTF-8 bytes; CR/LF/tab are preserved;
other ISO controls and unpaired surrogates rejected. Status is OPEN or ANSWERED;
ANSWERED requires a Java-isBlank nonblank answer. Answers/context have independent
bounds so ten worst-case escaped history snapshots remain within Gateway's2 MiB
response bound. The exact JSON transport limit remains256 KiB.

TLS, same-origin mutations, singleton secure guest/account browser credential,
no-store, strict duplicate/trailing JSON rejection, exact singleton known queries,
and empty GET/DELETE bodies apply. Strong independent `quN` tags guard writes:
missing428, malformed400, stale412 checked before no-op. Current unchanged writes
emit no revision/event. Known archived GET/history200, archived edit404; DELETE204
including matching current archive no-op. A 412 preserves drafts and requires an
explicit latest inspection/restart; archive confirmation resets on conflict.

Creation requires one random32-lowercase-hex Idempotency-Key. Same Space/key/input
replays original v1/qu1 even after edits, archive, restart or registered claim;
changed input409. Concurrent same-key requests produce one canonical object/event.
Controlled Outbox failure rolls back mutation/revision/key. Lost create responses
freeze the same browser input/key for retry. Private fields/keys live in memory,
never localStorage/sessionStorage. Inspect the catalog before starting a new
intent after abandoning an uncertain request. Account claim preserves IDs,
revisions, original guest actors and keys; later edits record the registered actor.

Canonical compact catalog summaries omit context/answer;20 default/100 maximum,
ascending UUIDv7 cursor bound to Space, lifecycle and status filter ALL/OPEN/ANSWERED.
Pages are live views. History is descending exclusive version,5 default/10 maximum.
Close, Space change and authority loss discard late private responses; failed
catalog reads clear stale rows. Status filter changes clear the selected record.

## Events and local operations

QuestionCreated/Updated/Retired use immutable content_question_v1.proto, literal
content.question.v1 and Registry content/question-events/1 with FULL validity and
FULL_TRANSITIVE compatibility. Full private revision payload, standard11 envelope,
original actor/Space and HTTP trace are stored in Outbox and reach Kafka through
the existing content-outbox Debezium connector/publication/slot. At-least-once
consumers deduplicate stable event IDs. Old seven Content schemas are checked read
only by Run-QuestionContractValidation.py, never rewritten.

Existing Connect producer and read-only Content validator receive only the new
literal topic permissions. Application projectors retain their original streams;
Question graph/search/activity projections and explicit domain links remain later
increments. The feature's canonical catalog reads its owner API, not another
owner database or an invented Note/Concept projection.

Provision-ContentCdc.py verifies Outbox SELECT, Note SELECT denial, four Bookmark/
Question canonical table SELECT denials, twelve other owner DB connection denials
and one-table publication. Existing data/PVCs/topics/offsets remain intact. Existing
Java25 virtual threads, reviewed pinned dependencies, own pool/readiness, safe
errors, bounded read/change counters and trace→Outbox→Kafka headers are reused.
Central OpenTelemetry exporters/Prometheus/Loki/Tempo/Grafana remain platform work.

Build/import/install Space, Content and Gateway independently using the existing
scripts and both k3d nodes. Run Run-BackendValidation.py with --content-kafka
--planning-kafka --notification-kafka --activity-storage. Focused browser:
Run-GraphWebValidation.py --questions-only; full browser: --public, including real
Keycloak claim checks. Evidence: [private-questions-validation.txt](private-questions-validation.txt).
