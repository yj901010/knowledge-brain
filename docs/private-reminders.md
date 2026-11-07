# Private Schedule reminder settings

Refresh `/graph` → **개인 일정 열기** → open an active or retired Schedule →
**리마인더 설정 열기**. Each setting has 0..10,080 elapsed minutes (seven days)
before the pinned Schedule start. Zero means at start. Saved settings are separate
from delivery intents: open the settings detail and explicitly arm **앱 내 알림
예약** to receive a persistent private notification. Existing settings never
auto-send. Notification Service owns arrival, read/archive and retry through the
separate [in-app delivery workflow](in-app-notifications.md). Email and OS push
remain subsequent work.

## Explicit source and time confirmation

**기준 일정·알림 시각 확인** reads the active current same-Space Schedule, then
performs a read-only server preview. It displays Schedule ID/version/title and
pinned start/end UTC/local/zone/offset, plus the resolved reminder UTC/local/offset.
Minutes are elapsed time, not local-wall-clock subtraction: subtracting across
DST can change the displayed offset. No gap normalization or ambiguous browser
guess occurs. UTC and resulting local years must remain 0001..9999. Settings
may describe past times; there is no inference that they were or will be sent.

The preview hash binds Space, exact input, full displayed source pins and all
resolved labels. Create/update lock the own Schedule row and recheck version/hash.
A changed or unavailable source requires another explicit preview. A stale preview
returns 412 with zero writes/key reservation. Each setting's Schedule link stays
immutable. Source edits, recurrence bulk archival, Task retirement and other
settings never implicitly rewrite stored pins or history. To adopt a new Schedule
version, edit the setting, preview and save again; even an unchanged minutes value
records the explicit new source pin. **현재 기준 일정 확인** reads current facts
separately and reports when the source is retired. Old pins remain readable.

## Lifecycle, retries and UI privacy

Each setting is ACTIVE or RETIRED, independently of source lifecycle. It can be
explicitly retired after the Schedule is retired. Known retired GET/history stay
available; catalogs filter active/retired and ALL/exact Schedule (including retired
ones). Stable ascending UUIDv7 catalogs use opaque Space/state/Schedule-bound
cursors (default20/max100); they are current pages, not frozen snapshots. Immutable
descending exclusive version history defaults to5/max10. No source row/revision,
Goal/Task status, Learning fact or prior schema is changed by these operations.

Every write is guarded by fresh Space authority and exact browser Origin. Preview
uses read authority. Strong independent `"rmN"` protects edits/retirement: missing
428, malformed400, stale412. Current no-op produces no revision/event. A matching
current retired DELETE returns204 without another mutation; an old ETag is412.
Creation uses a Space-scoped 32-hex key. Identical input+preview-hash replays the
original v1 after source/setting retirement or account claim; changed intent is409.
Distinct keys intentionally create distinct settings even at equal offsets.

Lost create replies freeze the exact request/key for retry. Confirmed rejected
inputs permit re-preview with the unreserved key. Update conflicts retain the
minutes draft until explicit discard/latest reopening. Retirement requires
acknowledgement, cleared on conflict. Failed reads clear stale rows; closing the
panel/Space change discards late private replies. Current revocation clears private
surfaces. Text remains escaped, mobile controls bounded, sibling Note/Schedule
drafts independent and no private local/session browser storage added.

## Ownership, contracts and rollout

AGENTS.md/PROJECT.md already assign Reminder to Planning and delivery to
Notification. This increment preserves those decisions, own DB tenancy, common
guest/registered principal, TLS, UUIDv7, local ACID, Transactional Outbox and CDC.
No architecture change, cross-service SQL/FK/join/transaction, dependency version,
external dataset or AI feature is introduced. The seven-operation
[OpenAPI](../contracts/openapi/private-reminders.yaml) reuses fresh existing
SCHEDULE_READ/WRITE. Exact JSON/integer bounds, singleton query names, empty
GET/DELETE bodies, 256KiB write limit and no-store follow existing Planning APIs.
Internal routes remain absent from Gateway.

Flyway V6 adds own reminder/reminder_revision and same-Space composite FK to own
Schedule. New immutable planning_reminder_v1.proto carries ReminderCreated/Updated/
Retired with standard envelope, full source pins, resolved reminder times,
revision/actor/trace and settings version. Existing planning_outbox publication,
planning_outbox_v1 slot and planning-outbox connector still capture only
public.outbox_event; the new aggregate routes to **planning.reminder.v1**.
Registry planning/reminder-events/v1 is FULL/FULL_TRANSITIVE. Existing schemas,
Activity's ten streams and all source facts/slots/offsets/PVCs stay intact.

1. Run scripts/Run-ReminderContractValidation.py; upgrade brain-connect with
   existing local values for the exact new producer topic ACL and fifth read-only
   Planning verifier topic. Reuse Provision-PlanningValidationClient.py.
2. Run Run-BackendValidation.py --planning --planning-kafka; full reactor adds
   --activity-storage. Use existing scoped image build/import/install scripts for
   Planning and Gateway only; no Space/Activity rollout is needed.
3. After V6, Provision-PlanningCdc.py verifies Outbox SELECT, eleven canonical
   table SELECT denials and twelve other-owner DB CONNECT denials. Existing
   one-table publication and connector need no reconfiguration.
4. Run Run-GraphWebValidation.py --reminders-only and the full --public suite.
   Bounded operation metrics and HTTP→Outbox→Kafka trace IDs are retained. Central
   observability deployment and actual Notification delivery are separate work.

Validation evidence: [private-reminders-validation.txt](private-reminders-validation.txt).


Explicit server-side dispatch and persistent private in-app inbox are now available: [in-app notifications](in-app-notifications.md). Settings require an explicit arm; existing settings do not auto-send.
