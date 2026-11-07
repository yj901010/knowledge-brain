# Private Planning tasks

Planning Service owns private Personal Space Tasks, their immutable revisions,
optional same-Space Goal ID and Transactional Outbox. This implements the existing
Planning ownership in AGENTS.md/PROJECT.md. No service/DB boundary, synchronous
protocol, event reliability or privacy decision changes. No new dependency
version, dataset or AI feature is introduced.

## Use

Refresh /graph → **개인 할 일 열기** → **새 할 일 작성**. Title, plain-text
description, manual status and optional literal due date are editable. Optionally
open the Goal picker, load its 20-row pages and select a stable Goal ID. Equal
titles remain distinguishable by ID. Tasks can be filtered by status, lifecycle,
all/unassigned/exact Goal; the filter's Goal picker can browse retired Goals.
Task catalog pages are 20 rows by default. History pages are five immutable
versions. **할 일 보관 상태 → 폐기된 할 일** rediscovers archived history.

Status is NOT_STARTED / IN_PROGRESS / COMPLETED, separate from ACTIVE / RETIRED.
Completion/reopening does not modify a Goal or Learning progress/session. Due date
is Gregorian YYYY-MM-DD, year 0001..9999, or null; it has no time of day, timezone
conversion, recurrence, reminder or automatic overdue/status effect. JDBC 4.2
LocalDate preserves dates at the 1582 cutover and year boundaries.

New Goal links must refer to an active Goal in the same private Space. Goal row
locking serializes a new link with Goal retirement. A retained link remains
readable/editable after Goal retirement; it can be explicitly cleared or replaced
with another active Goal. Re-adding an unlinked retired Goal fails. Goal retirement
does not cascade into Task edits/retirement. Task history stores the Goal ID as it
was; Goal renaming never rewrites Task versions. No historical Goal title pin is
claimed: the picker shows current labels and IDs, while Task details show the ID.

Note, Goal and Task drafts are independent. 412 preserves the Task draft and
requires explicit latest inspection/restart. Retirement requires acknowledgement
which is cleared on conflict. A lost create response freezes the intent and
retries the same key. A confirmed 400/404/413/415 rejection preserves editable fields so an unavailable Goal can be replaced, keeping the unreserved key; a conflicting 409 or uncertain response keeps the intent frozen. Closing does not persist pending drafts. Refresh the catalog
before beginning another creation intent. Errors retain write inputs; failed reads
clear stale rows. Close/scope/filter changes discard late reads. Current 401/403
or missing private Space clears private surfaces. Nothing private is stored in
browser local/session storage. All text is escaped without remote fetching.

## API, storage and events

[Task OpenAPI](../contracts/openapi/private-tasks.yaml),
[Space authority](../contracts/openapi/space-planning-authorization.yaml).
GET/POST /api/spaces/{spaceId}/tasks; GET/PUT/DELETE …/{taskId};
GET …/{taskId}/revisions are the six browser operations. Fresh TASK_READ/TASK_WRITE
uses the existing dedicated internal Planning capability and common application
principal; PERSONAL/PRIVATE only. Visible viewers read; OWNER/ADMIN/EDITOR write.
TLS, no-store, exact Origin on writes, empty GET/DELETE bodies, singleton queries,
strict JSON and bounded Gateway headers/bodies follow the existing Goal boundary.
Gateway never exposes the internal authorization route.

Input has exactly title, description, status, nullable dueDate and nullable goalId.
Title is nonblank, <=200 Unicode codepoints/800 UTF-8 bytes with no controls;
description <=32768 UTF-8 bytes permits CR/LF/tab. Dates and lowercase UUIDv7
references are strict; unknown/duplicate fields and injected actors are rejected.
Strong independent "tkN" ETags are required on PUT/DELETE: missing 428,
malformed 400, stale 412. Version is checked before no-op comparison. A current
no-op creates neither revision nor event.

POST uses one 32-lowercase-hex Idempotency-Key, unique per Space in the Task table.
First create returns 201. Same key/validated five-field intent returns 200 with
the original v1 snapshot/ETag after edits, retirement, Goal retirement or guest
Space claim; a different intent returns 409. Retries do not duplicate Task,
revision or event. Read the current Task separately.

Catalog is ascending Task UUID keyset, limit 1..100/default20, status ALL/default
or a manual status, state ACTIVE/default or RETIRED, goalId ALL/default, NONE or
an exact UUIDv7. It echoes the goalFilter and is a current catalog rather than a
multi-request frozen snapshot. UI resets cursors for changed filters. Revision
history is descending exclusive before-version, default5/max10. Retired current
GET/writes return 404; private archived discovery/history remain available.

Flyway V3 adds Planning-owned task/task_revision and a same-owner, same-Space
deferred composite FK to goal. No cross-service SQL/join/FK is introduced. Task
write, immutable revision and Protobuf Outbox commit/rollback together. No prior
Goal IDs, versions, revisions/events or v1 schemas are changed.

Existing planning_outbox publication, planning_outbox_v1 slot and planning-outbox
connector still capture only public.outbox_event. Aggregate task routes to the
new exact **planning.task.v1** topic. Registry planning/task-events/v1 is immutable
with FULL validity and FULL_TRANSITIVE compatibility. TaskCreated / TaskUpdated /
TaskRetired carry full snapshots and standard event/Space/actor/trace envelope;
empty due_date/goal_id mean null. Kafka key is Task ID. At-least-once delivery
requires future consumers to deduplicate event ID. No offsets, slots, topics,
PVCs, user facts or source events are reset/deleted.

## Local delivery

Publish/check Run-TaskContractValidation.py; build/import/install Space, Planning
and Gateway with the existing scoped scripts. Wait for both node import Jobs and
Ready. Helm upgrade brain-connect with the local values adds only the exact Task
topic/producer/validator ACL entries. Provision-PlanningValidationClient.py reuses
the dedicated read-only two-topic TLS client. After V3, Provision-PlanningCdc.py
verifies Outbox SELECT, four canonical table SELECT denials and 12 other owner DB
CONNECT denials; existing connector config/publication/slot are retained.

Run-BackendValidation.py --planning --planning-kafka checks both Goal and Task;
the full reactor can add --activity-storage. Run-GraphWebValidation.py --tasks-only
checks deployed Tasks; --public includes real guest-to-account preservation and
all existing browser flows. Use verified local HTTPS without TLS bypass.

Planning remains an independent service with own DB pool metrics/readiness,
virtual threads, TLS, restrictive ingress and nonroot/read-only runtime. Task
operations add bounded read/change metrics; HTTP trace IDs reach Outbox/Kafka.
Central observability/export dashboards remain platform work.

Validation: [private-tasks-validation.txt](private-tasks-validation.txt).
Task/Goal events are not yet part of the Learning-only activity timeline. Graph
projections, Task-to-Concept links, priorities/subtasks/dependencies, scheduling,
recurrence, reminders and notifications are separate future work.

One-time schedules can now optionally refer to Tasks through the separate [Schedule API/UI](private-schedules.md). Task status/due date and all existing inputs remain independent.


Planning Goal/Task/Schedule events now join the [unified private activity timeline](planning-activity-timeline.md); this extends the earlier Learning-only scope without changing canonical ownership or existing event versions. The panel is now **활동 열기**.
