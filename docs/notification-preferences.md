# Private Space in-app notification preferences

Refresh `/graph` → **알림함 열기** → **알림 수신 설정 열기**. Clear or check
**앱 내 알림 수신 허용**, acknowledge the displayed change and save. This applies
to the selected Personal Space, including after guest account claim. Another
Personal Space keeps its independent setting. The default is enabled.

**알림 미수신 이력 열기** shows immutable reminders processed while reception was
disabled, with the exact preference revision, original Schedule/Reminder versions,
local/UTC offset and dispatch identity. These records never turn into inbox items
when reception is enabled again. Existing notifications retain their read/archive
state. The setting does not cancel Planning intents or edit their sources.

## Processing boundary and canonical ownership

Notification owns the preference and missed records in its own PostgreSQL. First
processing of a previously unseen ReminderDispatched uses the preference at that
processing instant, including catch-up after downtime or canonical recovery. It
does not infer the preference from the intended reminder time or dispatch time.
A single own-DB transaction lock keyed by Space serializes setting changes and
first-time consumption, so a concurrent toggle has exactly one stable outcome.

Enabled creates the existing Notification + revision + Outbox. Disabled commits
Inbox hash + immutable suppression + new Outbox, including exact source envelope
bytes and an own-FK to the immutable disabled preference revision. Both commit
before Kafka acknowledgment. Failures roll back the full transaction and retry.
Identical redelivery/recovery remains a no-op after toggling, service restart or
read/archive changes; changed bytes/event IDs for an existing dispatch fail without
acknowledgment. No silent drop, cross-owner SQL, canonical reset or backfill upon
re-enabling occurs. Non-dispatch intents never create missed records.

The API's default enabled `np0` is virtual. Reads and current no-op writes create
no row, ID, revision or event. The first actual change allocates a Notification-
owned UUIDv7 aggregate and revision; subsequent changes preserve it. Strong `npN`
prevents stale or uncertain writes from overwriting a newer setting. Missing tag
is428; stale412, malformed400, oversized413, wrong media415. Current no-op emits
nothing, but stale tags fail even if the desired value already matches.

## Private APIs and UI

Four operations are documented in
[private-notification-preferences.yaml](../contracts/openapi/private-notification-preferences.yaml):

- GET/PUT `/api/spaces/{spaceId}/notification-preference`
- GET `/api/spaces/{spaceId}/notification-preference/revisions`
- GET `/api/spaces/{spaceId}/notification-suppressions`

Every request rechecks the existing dedicated NOTIFICATION_READ/WRITE capability
and common guest/registered principal through Space, PERSONAL/PRIVATE only.
Existing role rules remain. Exact bounded boolean JSON, duplicate/trailing field
rejection, TLS, same-origin mutation, empty GETs, singleton known queries and
no-store apply. No browser creation/edit/deletion of missed records is exposed.
Settings audit uses descending exclusive versions (5 default/10 maximum). Missed
history uses ascending opaque cursors bound to Space and record type (20/100),
live pages rather than a frozen snapshot. Version zero has no audit entry.

The browser requires explicit acknowledgment, refreshes after uncertain/conflicting
writes, never automatically retries a toggle, escapes source titles, retains no
private browser storage, discards late replies after close/Space change and clears
private panels on authority loss. The mobile layout stays inside390px. Settings
and missed history are independently opened and refreshed.

## Compatibility and local validation

V2 adds three own tables: preference, immutable preference revisions, and immutable
suppression. V1 migrations and every existing event schema remain unchanged.
New frozen `notification/delivery-policy-events/1` carries preference changes and
suppression facts through the existing Outbox-only Notification connector into
literal `notification.delivery-policy.v1`. Standard11-field envelopes preserve
the HTTP trace for changes and original dispatch trace/actor for missed records.
The Connect producer and Notification validator get only the additional literal
topic permissions; the application consumer retains its old read-only dispatch
topic. No other service, database grant, connector slot or offset is restructured.

CDC can SELECT only Outbox, cannot SELECT seven own canonical tables and cannot
CONNECT twelve other owner databases. The new schema gets FULL validity and
FULL_TRANSITIVE compatibility verification with exact source SHA256. Existing
reviewed dependencies and Java25 are reused. API/read/worker counters, readiness,
safe retry logs and event trace propagation remain; central OpenTelemetry and
the shared observability dashboards remain platform work.

Run `Run-NotificationPolicyContractValidation.py`, upgrade the existing brain-connect
chart, provision the validator with `Provision-LocalNotifications.py`, then build,
import on both nodes and install Notification and Gateway independently. Run
`Provision-NotificationCdc.py` after migration. Do not reset the existing connector.
Validate backend with `--planning-kafka --notification-kafka --activity-storage`,
focused browser `--notification-preferences-only`, then full browser `--public`.
Recorded results: [notification-preferences-validation.txt](notification-preferences-validation.txt).
