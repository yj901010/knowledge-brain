# Private Planning activity in the unified timeline

Current operational scope (2026-10-18): [Note Concept activity](note-concept-activity.md) extends the timeline to seventeen streams across Learning, Planning and Content. Use its current rollout/recovery instructions; the earlier increment and validation evidence below remain historical.


Refresh `/graph` → **활동 열기**. This extends the existing Learning timeline
with Goal, Task and Schedule creation, edits and archival. Type filters select
목표 / Task / 일정; ALL includes all ten current event streams. Date/timezone,
descending save-time/event-ID ordering, 20-row pages and fresh private Space
TIMELINE_READ authorization apply to every page. Existing Learning operation ID
and route are retained. This is an activity history; an event appears when a
change was saved, not on its goal/due/scheduled date. Recent writes are eventual.

Details show immutable historical title/description, version, lifecycle and
actor. Goal/Task progress status and literal target/due date remain separate
from lifecycle and timezone. Only linked Goal/Task IDs are pinned, without
claiming their current titles or status. Schedules preserve UTC instants and
the exact local/timezone/UTC-offset labels in the original event; no future
TZDB recalculation changes history. Study-session duration is not inferred
from a Schedule. **현재 활동 원본 확인** explicitly reads the matching authorized
canonical Goal/Task/Schedule. Historical details remain when the source was
edited or archived. No timeline action modifies a canonical fact or another
Note/Goal/Task/Schedule/Learning editor draft. Text is escaped and mobile width
bounded. Closing, filtering, switching Spaces or revoked authority clears and
cancels private requests; late replies cannot reopen a private panel.

## Ownership and event reliability

AGENTS.md/PROJECT.md assign canonical Goal/Task/Schedule to Planning and timeline
projections to Activity/ClickHouse. This follows those decisions, reuses the
current dependencies and adds no AI, dataset, new service or event version.
Ten immutable event schemas, original CDC publications/slots and offsets stay
unchanged. Planning → local ACID business/revision/Outbox → Debezium → existing
three Planning v1 topics → Activity-owned PostgreSQL Inbox → ClickHouse. Exact
wire hash plus stable event ID deduplicates/rejects conflicts; aggregate/topic/
version uniqueness and ClickHouse FINAL retain existing at-least-once semantics.

Activity's dedicated Kafka TLS user now has literal Read/Describe for exactly
seven Learning + three Planning topics and its existing prefixed consumer group.
It gains no write/DDL/other-topic or canonical DB capability. Existing ClickHouse
reader/projector separation, private HTTPS/CA verification, durable queue,
bounded batches and responses (16 MiB ClickHouse / 8 MiB Gateway, enough for 100 maximally escaped 32 KiB descriptions), metrics and event/trace correlation are reused. The central
observability stack remains platform work, not a completed deployment claim.

## Recovery and rollout

Planning exposes `GET /internal/planning/activity-events` only when enabled,
with a separate Planning export key, constant-time comparison, no browser cookie,
strict UUIDv7/singleton queries, no-store and read-only five-row pages. It fixes
an inclusive event-ID high watermark and returns exact retained immutable own
Outbox bytes for goal/task/schedule. Learning keeps its separate export and key.
The API has no Gateway route and never accepts a user Space/session as export
authority. [Internal API](../contracts/openapi/activity-internal.yaml),
[timeline API](../contracts/openapi/activity-timeline.yaml).

V2 changes only Activity's readiness/seed flags; all existing Inbox rows are
preserved. Startup captures assigned ten-topic end offsets before scanning both
owners with separate fixed watermarks, then tails captured offsets. This covers
concurrent writes and recovers retained history across Kafka retention gaps.
No individual owner scan marks the feed seeded. An unavailable owner leaves
the initial/rebuild feed unavailable until recovery succeeds and the durable
queue drains. Later live projection remains eventual, without an exactly-once
claim. Empty history is valid. Retain both owners' Outboxes and Activity Inbox
or backups; purging recoverable history is outside this work.

1. Run `python scripts/Provision-LocalActivity.py` to reuse existing credentials
   and create the distinct Planning export key plus exact three added read ACLs.
2. Build/import/install Planning first so the private export and scoped network
   ingress are Ready, then Activity, then Gateway. Use the existing local image
   scripts and `Install-LocalRoadmaps.py` single-service flags with Helm.
3. Activity V2 automatically performs the two-owner initial warmup. Existing
   consumer group, seven Learning offsets, all canonical rows/PVCs/topics/slots
   are preserved; no destructive reset is required.
4. `python scripts/Rebuild-LocalActivity.py` uses the deployed image and two
   narrow export keys to replay both owners plus durable Inbox. It changes no
   canonical fact, table/PVC, topic or Kafka offset. Its Pod cannot match the API
   Service. Readers return 503 during rebuild; a failed rebuild stays unready
   for repair/rerun. A persistent ClickHouse restart needs no rebuild.

Validation: `Run-BackendValidation.py --planning-kafka --activity-storage` runs
real PostgreSQL export/Inbox, three Planning producer streams and ten-topic
Kafka/ClickHouse reader tests. `Run-GraphWebValidation.py --planning-activity-only`
targets the new scenarios; the full public run also tests real Keycloak claim
and all existing browser flows. Results: [planning-activity-validation.txt](planning-activity-validation.txt).

Recurrence, reminders/notification delivery, graph/search projections for
Planning, calendar synchronization and other domain activity remain separate.
