# Concepts linked to private records

On `/graph`, open a Bookmark, Question or Decision → **기록 개념 연결 열기**.
Select private Concepts from the paged catalog or public Concepts from the public
picker, then explicitly **기록 개념 연결 저장**. At most20 ordered Concept IDs.
Uncheck/remove to detach, or move an item upward. Original record fields and their
version/history remain unchanged. Association editing stays independent while a
source body draft is open; source body edits likewise do not replace link drafts.

Use **기록 연결 이력 열기** for immutable snapshots/actors/source versions. Link IDs
remain stable when retained or reordered; detaching then re-adding creates a new
link ID. Lost PUT replies and412 keep the selection and block resave until explicit
**최신 기록 연결 확인** / **기록 연결 초안 버리고 최신에서 다시 시작**. No silent merge.
Confirmed unavailable new targets are rejected while retaining the selection.

The personal Concept library now includes **개념의 관련 기록 열기**. Filter type and
active/archived lifecycle, page the catalog, and open current canonical record
contents. The record's linked ID list also offers **관련 기록 보기**, including for
public or retired targets. References are private to the selected Personal Space.
Reading an external Bookmark address never fetches it; reverse detail is plain
escaped text. Record forms and existing Note/Concept navigation remain independent.

## Canonical owner and concurrency

Existing PROJECT.md assigns Content-Concept relationships to Content. Additive V11
owns record_concepts, record_concept_link and record_concept_revision in
brain_content. Independent association UUIDv7, rcN version, ordered link IDs,
immutable audit and Protobuf Outbox bytes commit in one local ACID transaction.
Initial links are associationId=null/version0/empty; an initial empty no-op creates
no row/event. Existing identical order is a no-op. Stale tags fail before no-op.

Typed source references are validated against Content-owned Bookmark/Question/
Decision rows. The relationship transaction locks the same parent row used by
source edits/archival, then checks current head version and ACTIVE state. This
serializes first-write creation and prevents writes after archival. Only local
association FKs are used: the polymorphic source is guarded in the owner transaction
and source rows are retained. No Knowledge SQL/FK/joins or other owner mutations.

Source archival retains association current/history without synthesizing a link
revision. Known archived sources remain readable; all link writes404 (stale tag
still412 first). Reverse catalogs use current source title/version/lifecycle in
Content's own SQL joins; default ACTIVE excludes archived records, RETIRED discovers
them. Public Knowledge/Contribution state is not changed. New association events
carry the source version/state at the committed mutation, not a later reinterpretation.

## Authority and API

[private-record-concepts.yaml](../contracts/openapi/private-record-concepts.yaml)
declares four operations: association GET/PUT, GET revisions and GET Concept reverse
records. Type is exactly BOOKMARK/QUESTION/DECISION. Each request rechecks that
source type's existing READ/WRITE capability via dedicated Content→Space credential
and common guest/registered principal. PERSONAL/PRIVATE only; visible members read,
OWNER/ADMIN/EDITOR write. No new Space action or service credential is needed.

Only newly added target IDs are checked through the existing authenticated HTTPS
Content→Knowledge concept-check API. Targets must be active same-Space private or
readable public Concepts. Existing references may retain/remove retired targets;
re-adding a detached retired target fails. Validation is at the synchronous check,
not a distributed lock on Knowledge. Later target retirement preserves references.
Reverse reads deliberately do not resolve the target or expose target text.

TLS, singleton secure browser identity, same-origin mutation, no-store, exact known
singleton queries, strict duplicate/trailing JSON and lower-case UUIDv7 IDs apply.
Input is exactly conceptIds, unique array max20,2 KiB JSON transport. GET bodies
must be empty. Strong rcN including rc0: missing428/malformed400/stale412. Current
authority/storage unavailable503 rolls back all writes. Body/domain histories and
creation idempotency keys remain unchanged. Account claim retains association,
link/revision IDs and guest actors; later relationship changes use registered actor.

Reverse20 default/100 maximum, ascending opaque cursor binds Space/Concept/type/
lifecycle. Pages are live views; current source detail is separately reauthorized.
History5 default/10 maximum, descending exclusive version. Closed panels, Space
changes and revocation discard late responses; failed reads clear stale results.
UI drafts/private fields are memory only, never browser storage. Public picker
uses existing public APIs and never resolves another Space's private metadata.

## Events and operation

New immutable content_record_concepts_v1.proto, literal content.record-concepts.v1,
Registry content/record-concepts-events/1 with FULL/FULL_TRANSITIVE. One event type
RecordConceptsChanged carries complete ordered association revision and standard11
envelope with stable event ID, Space/actor/trace. Aggregate ID is association UUID,
source kind+ID explicit; three types cannot collide. Outbox→existing Content
publication/slot/Debezium→Kafka preserves offsets. At-least-once consumers deduplicate
event IDs. Nine previous Content contracts are verified read only and unchanged.

Only Connect producer and read-only Content verifier gain the new literal topic.
App projectors retain their existing streams; integrated Graph/search/activity
projection of these record associations is a separate increment, using parent and
association events together rather than fake Note nodes or cross-owner SQL.

Provision-ContentCdc.py verifies Outbox SELECT, Note and nine Record/Concept table
SELECT denials, twelve other owner DB CONNECT denials and one-table publication.
No data/PVC/topic/slot/offset reset. Reviewed pinned dependencies, Java25 virtual
threads, own pool/readiness, safe errors, bounded association counters and HTTP→
Outbox→Kafka traces are reused. Central telemetry exporters/platform remain future
work. No dependency, dataset, automatic fetch, public publication or AI introduced.

Build/import/install Content and Gateway on both k3d nodes; Space/Knowledge binaries
and permissions remain. Backend flags: --content-kafka --planning-kafka
--notification-kafka --activity-storage. Browser --record-concepts-only, full
--public. Evidence: [record-concepts-validation.txt](record-concepts-validation.txt).
