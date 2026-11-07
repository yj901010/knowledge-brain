# Private one-time Planning schedules

Schedule remains owned by Planning as established in AGENTS.md/PROJECT.md. The
feature uses the existing owner DB, common principal, dedicated Space capability,
TLS, UUIDv7, local ACID/Outbox/CDC/Kafka and independently deployed service.
No architecture decision, dependency version, external dataset or AI scope changes.

## Use and time semantics

Refresh /graph → **개인 일정 열기**. Choose an inclusive start/end date and
query timezone; up to 93 dates per window. The agenda shows intervals overlapping
that window, ordered by UTC start then stable ID. A long schedule spanning the
whole window is included; merely touching its start/end is excluded. Current
rows are not a frozen snapshot across pages. Refresh starts over. Filters include
active/retired and all/unassigned/exact Task; the filter can browse retired Tasks.

**새 일정 작성** takes title/plain description, start/end local seconds, an
explicit IANA timezone and optional same-Space Task. Press each time's **확인**.
The read-only server resolver returns zero candidates for a DST gap, one for a
unique time, or two for an overlap. A gap cannot be saved; overlapping times need
an explicit offset choice. Half-hour/historical second offsets are supported.
Two identical wall times can form a positive interval when different overlap
offsets are selected. End must follow start in UTC. There is no automatic gap
shift or guessed overlap offset. Changing a local time clears its candidate;
changing zone retains local inputs and clears both candidates for explicit
resolution. Unrelated fields edited during resolution are preserved.

API writes store strict UTC whole-second instants (YYYY-MM-DDTHH:mm:ssZ,
years 0001..9999), zone ID, and local/offset labels pinned at mutation time in the
immutable snapshot/event. Fractions, leap seconds, offsets in UTC fields, 24:00
normalization, unknown/fixed-offset zone IDs and nonpositive intervals are rejected.
Both corresponding local years must also remain 0001..9999. GET/history return
the pinned labels rather than recalculate under a future timezone database.
No-op comparison happens before regenerating labels; retirement retains them.
Explicit zone/time edits regenerate labels under current ZoneRules while UTC
source instants remain those supplied by the caller. The browser resolver uses
the server's ZoneRules, not browser guesses. JDBC 4.2 OffsetDateTime preserves
instants across the Gregorian cutover, year boundaries and DB session zones.

Date windows use the named ZoneId's atStartOfDay through the following day's
start: DST days may be 23/25 hours; an entirely skipped local date with zero
duration is rejected. Half-open overlap is startsAt < windowEnd AND endsAt >
windowStart. A created schedule outside the chosen window remains available by
ID/history; change dates to rediscover it in the catalog.

New Task links require an active Task in the same private Space. Row locking
serializes them with Task retirement. Existing retired-Task links remain usable
for unrelated Schedule edits, can be explicitly cleared or replaced, and remain
in past versions; re-adding a retired Task fails. Scheduling/moving/retiring never
changes Task status/due date, Goal status or Learning records. Task and Schedule
labels/versions are independent; the picker displays current Task title/ID,
while Schedule details/audit retain the Task ID without claiming a title pin.

Schedule/Task/Goal/Note drafts are independent. Lost creates freeze the exact
intent and retry the same 32-hex key; confirmed 400/404/413/415 rejection leaves
inputs editable for correction with the unreserved key. 409/uncertain outcomes
keep the intent frozen. Closing does not persist pending drafts: inspect the
catalog before starting another intent. 412 preserves edits until explicit
latest inspection/restart; retirement acknowledgement resets on conflict.
Read failures clear stale rows; close/scope/filter changes discard late replies.
Current revoked/denied sessions clear private surfaces. No private browser
storage, markup execution or automatic external resource request is added.

## Contracts, storage and deployment

[Schedule API](../contracts/openapi/private-schedules.yaml) has six CRUD/history
operations plus GET …/schedules/time-options. Input has exactly title,
description, startsAt, endsAt, timeZone, nullable taskId. Title/description reuse
the Goal/Task Unicode/UTF-8 bounds. Fresh SCHEDULE_READ/SCHEDULE_WRITE is checked
for each call, including resolver/pages, using the existing internal Planning
capability and common guest/registered principal. PERSONAL/PRIVATE only; visible
roles read and OWNER/ADMIN/EDITOR write. Same-origin writes, TLS, no-store,
singleton known queries, bounded payloads and empty GET/DELETE bodies apply.
Gateway exposes only the exact declared browser routes; internal authority is
not routed. Independent strong "scN" ETag: missing 428, malformed 400, stale 412;
current no-op creates neither revision nor event.

Create key is unique per Space in Schedule, independently of Task/Goal keys.
First create is 201; identical validated six-field intent replays original v1
snapshot/ETag as 200 after edits, retirement, Task retirement or account claim.
Different intent with the same key is 409. Read current separately. Revisions
are immutable, descending exclusive version pages (default5/max10). Retired
current GET/writes are 404 while archived catalog/history remain readable.

Agenda requires from/to/timeZone, default state ACTIVE and taskId ALL (or NONE /
UUID), default20/max100, with an opaque canonical base64url after cursor bound to
Space, state, Task filter, dates and zone. It carries UTC start/ID for stable equal
start ordering. It is untrusted positional data, not authorization or a frozen
snapshot. Authorization and hard SQL tenancy/window filters apply every time.

Flyway V4 adds own schedule/schedule_revision, indexes, positive interval check
and same-owner same-Space deferred composite Task FK. Source mutation, revision
and full Protobuf event commit/rollback together. Existing sources, immutable
Goal/Task/Learning event v1 contracts and their business semantics are preserved.
No cross-service SQL/join/FK or distributed transaction is introduced.

Existing planning_outbox publication, planning_outbox_v1 slot and planning-outbox
connector still capture only public.outbox_event; aggregate schedule routes to
**planning.schedule.v1**. Registry planning/schedule-events/v1 has FULL validity
and FULL_TRANSITIVE compatibility. ScheduleCreated/Updated/Retired carry standard
event/Space/actor/trace envelope and full pinned snapshot; empty task_id means
null. Kafka key is Schedule ID. Future consumers must deduplicate event ID under
at-least-once delivery. No existing slot/offset/topic/PVC/table/fact is reset.

Publish Run-ScheduleContractValidation.py; build/import/install Space, Planning
and Gateway with existing scoped scripts and both node import checks. Upgrade
brain-connect with local Helm values for the exact Schedule topic/producer ACL.
Provision-PlanningValidationClient.py reuses the exact three-topic read-only TLS
validator. After V4, Provision-PlanningCdc.py checks Outbox SELECT, six canonical
table SELECT denials and 12 other owner DB CONNECT denials, preserving publication
and connector config. Central observability/export dashboards remain platform
work; bounded read/change metrics and HTTP→Outbox→Kafka trace IDs are supported.

Run-BackendValidation.py --planning --planning-kafka exercises all three Planning
aggregates against own PostgreSQL/Kafka; full reactor can add --activity-storage.
Run-GraphWebValidation.py --schedules-only checks deployed Schedule flows;
--public includes real guest-to-account continuity and existing browser features.
Validation: [private-schedules-validation.txt](private-schedules-validation.txt).

This step is timed one-time schedules. All-day events, recurrence, reminders,
notification delivery, shared calendars, collision prevention, external calendar
sync and Schedule/Task/Goal graph projections remain separate work. Activity projections are now available in the unified timeline.
Overlapping different schedules are allowed. This is a plan, not a recorded
Learning study session or a task completion action.


Planning Goal/Task/Schedule events now join the [unified private activity timeline](planning-activity-timeline.md); this extends the earlier Learning-only scope without changing canonical ownership or existing event versions. The panel is now **활동 열기**.

Finite DAILY/WEEKLY recurring schedules now use the same Planning ownership and unchanged Schedule v1: [private recurrence](private-recurrences.md). No dependency version, dataset or AI feature was added.


Private Schedule reminder settings and immutable time/source confirmation are now available: [reminder settings](private-reminders.md). Actual notification delivery remains subsequent Notification Service work.
