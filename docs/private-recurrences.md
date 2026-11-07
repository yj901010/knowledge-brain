# Private finite recurring schedules

Refresh `/graph` → **반복 일정 열기** → **새 반복 일정 작성**. Specify the
first local start/end, exact IANA zone, DAILY or WEEKLY interval, 2..100 occurrences
and optional active same-Space Task. Daily interval is 1..365; weekly 1..52. All
years remain 0001..9999. This is a finite series, materialized completely when
confirmed; no background tick, infinite rule, end-date, monthly/RRULE import,
skip exception, reminder or notification delivery is claimed in this step.

**반복 회차 미리보기** shows every resolved UTC/local/offset pair. Calendar
days/weeks preserve wall time through DST, rather than adding fixed UTC hours.
A gap returns GAP and stops creation; it never shifts or silently skips an
occurrence. Each endpoint's REJECT policy stops on overlaps until EARLIER/LATER
UTC is explicitly selected. Nonpositive intervals/year overflow are reported.
No writes occur during preview. The hash binds Space, exact input and all
resolved pins; changed results require a new preview. Existing pins never
recalculate under future timezone rules. No Task status/due date, Goal, Learning
or other draft is changed. Overlapping different schedules remain allowed.

Confirming creates the series and all child Schedules in one Planning-local
transaction, with their immutable revisions and Outbox bytes. Partial parent,
child, linkage or event writes roll back together. Children use unchanged
Schedule v1 IDs/API/events and appear in the existing agenda/activity timeline.
Each child can be edited or archived independently in **개인 일정 열기**. Series
details show original pins; **현재 회차 확인** explicitly reads current Schedule
facts. A retired current child returns unavailable without erasing original pins.
The source rule is immutable in this step; editing all future rule parameters
is separate work, rather than silently rewriting original or individually
edited occurrences. A series never auto-completes based on wall time.

## Guarded bulk archival

Choose an explicit **보관 시작 회차**; this uses original ordinal, even if a
child was individually moved. **보관 대상 미리보기** lists current ACTIVE
children from that ordinal with current IDs/titles/times/versions. Previously
archived children are excluded. After explicit agreement, the service locks
all relevant child rows in stable ID order and compares the exact preview hash
and independent parent `"rcN"` ETag. Any intervening change returns 412 with
zero partial archival; preview and acknowledge again. Selected current child
snapshots are retained in their own retirement revisions. Earlier ordinals and
already archived history are unchanged. The parent becomes RETIRED, retaining
its original rule/pins and chosen starting ordinal/hash. Earlier active child
Schedules stay independently usable. Even zero remaining active targets may
explicitly retire the series. This is not an implicit time-based cutoff.

An identical cancel retry with the original/current parent ETag and same cutoff/
hash returns the existing retired result without another child/event mutation.
Changed retired cancellation intent returns 412. Known parent GET and history
remain readable after retirement, and the catalog can filter retired parents.
History uses descending exclusive version pagination; current parent versions
are v1 create and v2 retire. Original creation key replays original v1 regardless
of child edits, parent/Task retirement or account claim. Changed creation intent
is 409. A stale creation preview is 412 and consumes no key. Uncertain browser
creates/cancels freeze the exact request for retry; confirmed rejections permit
new preview. No private browser storage is used. Close/Space changes discard
late private replies; revoked sessions clear the private surfaces. Plain text
is escaped and mobile layout bounded. Sibling drafts remain independent.

## Ownership, contracts and rollout

AGENTS.md/PROJECT.md already assign Recurrence and Schedule to Planning. This
preserves separate services/DB ownership, common guest/registered principal,
Space tenancy, TLS, local ACID and Transactional Outbox/CDC. There is no
cross-owner SQL/FK/transaction or new dependency/dataset/AI. The seven-operation
[API](../contracts/openapi/private-recurrences.yaml) reuses existing fresh
SCHEDULE_READ/SCHEDULE_WRITE capability; preview reads, create/cancel write.
All POSTs require exact same origin; only create requires a 32-hex key. Internal
export routes remain absent from Gateway. Strict exact JSON fields/singleton
queries, 256 KiB body bounds, strong rcN and no-store apply.

V5 adds own recurrence/revision/occurrence mapping and same-Space composite FKs
to own Schedule. It preserves existing source rows/revisions. New immutable
`planning_recurrence_v1.proto` emits RecurrenceCreated/RecurrenceRetired through
the existing planning_outbox publication/slot/connector to **planning.recurrence.v1**.
The new Registry planning/recurrence-events/v1 has FULL/FULL_TRANSITIVE rules.
Each child continues emitting ScheduleCreated/Retired; parent metadata is not
added to frozen Schedule v1 or ten existing schemas. Activity continues reading
its ten existing streams and displays child actions; a separate parent-series
activity/graph/search projection is not claimed. Existing CDC slots/offsets,
topics/PVCs/tables and immutable events are never reset or deleted.

1. Publish/check `Run-RecurrenceContractValidation.py`.
2. Helm upgrade brain-connect with the existing local values. Only the literal
   new topic/producer ACL and fourth read-only Planning verifier topic are added;
   Activity remains restricted to its existing ten topics. Provision/reuse the
   Planning validation client. No connector or slot reconfiguration is needed.
3. Run `Run-BackendValidation.py --planning --planning-kafka` against real own
   PostgreSQL/four scoped Kafka topics. Full reactor adds `--activity-storage`.
4. Build/import/install Planning and Gateway independently with existing local
   scripts and exact both-node image checks. Space and Activity need no rollout.
5. After V5, `Provision-PlanningCdc.py` checks Outbox SELECT, nine canonical table
   SELECT denials, 12 other owner DB CONNECT denials and original one-table
   publication. Operation metrics and trace IDs are retained; central monitoring
   stack/dashboard deployment remains separate platform work.

Browser verification: `Run-GraphWebValidation.py --recurrences-only`, then full
`--public`. Evidence: [private-recurrences-validation.txt](private-recurrences-validation.txt).


Private Schedule reminder settings and immutable time/source confirmation are now available: [reminder settings](private-reminders.md). Actual notification delivery remains subsequent Notification Service work.
