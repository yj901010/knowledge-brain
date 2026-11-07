# Durable private in-app reminder notifications

Refresh `/graph` → open a Schedule and Reminder → **앱 내 알림 예약 열기** →
acknowledge the displayed version/time → **앱 내 알림 예약 확정**. Open **알림함
열기** and refresh after the scheduled time. Opening a notification never marks it
read automatically. Read/unread, explicit archival, old/current source comparison,
history and active/retired filters are independent of the original Schedule.
No browser tab needs to stay open. This channel is the persistent application
inbox; email, mobile/OS push and sound remain subsequent work. Space-wide receive/pause preferences and immutable missed history are now available: [notification preferences](notification-preferences.md).

Existing Reminder settings never automatically become delivery intents. Arming
requires an active current Reminder, its exact current version and an active same-
Space Schedule still matching the pinned version, with a strictly future reminder
time. If a source changed, explicitly re-preview/save the Reminder and arm again.
Elapsed UTC-minute and immutable local/zone/offset rules stay unchanged. Settings
may still describe past times, but those cannot be newly armed.

## Local transactions and the cancellation boundary

Planning V7 owns reminder_delivery/revision and its local same-Space references.
Each explicit intent has its own UUIDv7, 32-hex key and dpN version. One pending
intent per Reminder is enforced under source locks and a unique partial index.
Same key and reminder/version replay the current attempt, even after source or
settings changes, cancellation, firing or account claim; changed intent is409.
Cancelling a PENDING attempt and explicitly rearming with a new key is allowed.
Fresh strong dpN prevents stale cancellation; terminal cancellation/suppression
at the matching current version is a no-op. DISPATCHED cannot be recalled.

The durable server worker locks own Schedule → Reminder → delivery. If either
source is retired or its version changed, the intent becomes SUPPRESSED, even
before the due time. An unchanged due attempt becomes DISPATCHED atomically with
an immutable revision, original-source dispatch envelope and Outbox record. Any
failure rolls everything back, leaving the same pending intent for retry. Multiple
workers/cancellation serialize to one terminal outcome. No Schedule/Reminder/Task/
Goal/Learning version is changed. Only one dispatch is committed per intent.

Dispatch commit is the cancellation boundary, not downstream display time. A
change after dispatch does not erase the historical notification and may precede
its eventual arrival. Service downtime produces a late catch-up delivery when the
source still matches. The UI separates intended reminder time, dispatch time and
inbox arrival time. Cancelling future intent does not retract already dispatched
facts. Source/archive changes preserve all original pins, keys and audit.

## Notification ownership and recovery

The independently deployed Notification Service consumes the literal read-only
planning.reminder-delivery.v1 stream. Only ReminderDispatched creates an inbox
item. Its own PostgreSQL transaction commits consumed-event hash, Notification,
immutable revision and notification.in-app.v1 Outbox before Kafka acknowledgment.
Duplicates with identical bytes leave existing read/archive versions intact;
conflicting event IDs or duplicate dispatch identities fail without acknowledgement.
Storage/dependency failure retries automatically and never acknowledges a failed
delivery. No service reads or changes another owner's SQL tables.

Cold start or a retained-log/committed-offset gap captures Kafka end offsets,
merges immutable dispatched envelopes through Planning's dedicated bounded TLS
capability API, then resumes those captured offsets. A concurrent old attempt
firing behind the export cursor is therefore covered by Kafka. The merge never
deletes notifications or overwrites read/archive state. Existing CDC slots,
connector offsets, topics, source rows, immutable events and PVCs are not reset.

Notification owns ntN read/unread/archive versions and their Outbox. Known retired
GET/history stays readable; archived read edits are unavailable. Current no-op
does not create another revision/event. Catalogs use ascending opaque cursors
bound to Space/lifecycle/read filter (20 default/100 max); history descending
exclusive versions (5 default/10 max). These are live pages, not frozen snapshots.
Settings/history, dispatch audit and inbox audit remain separate facts.

All external calls recheck current common guest/registered authority through
Space. Notification gets a separate capability limited to NOTIFICATION_READ/WRITE;
visible Personal/Private roles read, OWNER/ADMIN/EDITOR write. Account claim keeps
Space/object/dispatch/notification IDs and original guest actors; subsequent read
changes record the registered actor. Strict TLS/Origin, singleton queries, bounded
exact JSON, empty reads/DELETE, no-store, explicit source comparison, escaped text,
discarded late reads, revoked-session clearing and no private browser storage apply.
Uncertain arm replies freeze the same request/key for retry; closed intents should
be inspected before another explicit arm. Read/archive conflicts require refresh.

## Compatibility and local operation

This follows AGENTS.md/PROJECT.md's existing Planning/Notification ownership, local
ACID, event reliability, private default and Kubernetes architecture. No existing
decision is restructured. New frozen contracts are planning/reminder-delivery-events
v1 and notification/in-app-events v1, both FULL/FULL_TRANSITIVE. The previous five Planning and seven Learning schemas remain byte-for-byte intact.
Activity retains its original ten read-only streams and does not own notification
facts. Notification uses a separate own DB, TLS/capabilities and exact read-only
Kafka identity. Its CDC role selects only own Outbox and cannot SELECT four own
canonical tables or CONNECT to twelve other owner DBs. Planning CDC similarly
denies thirteen canonical tables. Original seven Connectors remain; Notification
adds an eighth Outbox-only connector/publication/slot, without resetting anything.

Use existing reviewed JDBC/Hikari/Flyway/Protobuf/Kafka libraries and pinned JVM/
image versions; no new dependency version, data licence or AI capability is added.
Bounded operational counters, readiness for worker/DB/consumer, safe retry logs
without payload/credentials and HTTP→stored trace→Outbox→Kafka propagation apply.
Central OpenTelemetry/metrics/log/tracing dashboards remain shared platform work.

Provision-LocalNotifications.py sets only local own TLS/DB/purpose credentials and
two exact read-only Kafka users. Prepare Notification CDC credentials before the
brain-connect Helm upgrade; migrate own schema before Provision-NotificationCdc.py
and Run-NotificationConnectorConfiguration.py. Publish Run-DeliveryContractValidation
and Run-InAppContractValidation, upgrade connect for two literal producer topics,
and retain the existing connector/slot offsets. Build/import on both nodes and
install Space, Planning, Notification and Gateway independently. Notification's
canonical recovery needs the new Planning export and new Space capability ready.

Run BackendValidation with --planning-kafka --notification-kafka --activity-storage;
browser Run-GraphWebValidation.py --notifications-only, then full --public. Evidence:
[in-app-notifications-validation.txt](in-app-notifications-validation.txt).
