# Feedback retraction/restoration annotations

On /graph/groups, the current feedback and each entry in feedback history shows
**피드백 vN 철회 상태**. Current OWNER can open **철회·복원 관리 열기**, enter a
reason, confirm that exact feedback version/audience and save a retraction or restoration.
Author reads the same marker, reason and paginated audit. Original comment stays visible.

## Meaning and compatibility

Retraction withdraws endorsement of a particular opinion. It does not erase or conceal
original text/history from its author/current OWNER. Existing feedback current/history
APIs and published V1 remain unchanged. No grade, verification, completion, resubmission
request, private progress or original Note change. Current/history audience stays
AUTHOR_AND_OWNER. Peer MEMBER/VIEWER gets404 even before a marker exists. Current
VIEWER authors read; removed authors lose Group access, while current OWNER retains
review access under existing Submission policy. Fresh Space is membership authority.

Each immutable feedback version has its own optional UUIDv7 retraction aggregate,
independent frN version and immutable full revision history. Absent GET returns null/fr0
without allocating anything. Initial RESTORED409. First RETRACTED creates version1;
later retraction/restoration/reason correction appends audit. Exact current same state/
reason is a no-op; stale same-value requests still412. No destructive deletion.

A new explicitly shared feedback version starts without an annotation. Earlier versions
keep their retractions/history. Saving unchanged original feedback is its existing
no-op and does not restore a retracted opinion. OWNER must explicitly restore it or
save a genuinely changed/new-answer comment. Historical feedback versions may also
be annotated, without rebinding them to a newer submitted answer or instruction.

OPEN/CLOSED Assignment + nonarchived Study writable, including historical versions.
RETIRED Assignment/Study or DRAFT is readonly with retained markers/history. Completed
Study is not archive. Closing an Assignment does not permit new answer submission.

## Implementation and contracts

Collaboration V11 owns feedback_retraction and feedback_retraction_revision. Local FKs
pin exact immutable feedback version, Submission/parent/author scope and marker audit.
Current/full revision/Outbox are one local ACID tx. Group -> Study -> Assignment ->
Submission locks serialize all marker writers with feedback/lifecycle changes. No
network while locked, cross-service SQL/FK, new owner/service/dependency/dataset or
distributed transaction. Old migrations/published events unchanged.

- contracts/openapi/feedback-retractions.yaml: GET/PUT
  .../feedback/revisions/{feedbackVersion}/retraction and GET .../revisions.
- Exact canonical positive int64 target. Strong If-Match frN, missing428, malformed400,
  stale412, archived/initial restoration409. Strict duplicate/trailing/unknown-field
  JSON, exact state/reason, body<=20480, nonblank valid Unicode/no NUL/unpaired surrogate,
  reason<=8192 UTF8 bytes. Bounded query<=256, history descending5/max10.
- New collaboration_feedback_retraction_v1.proto full snapshots FeedbackRetracted /
  FeedbackRestored. Topic collaboration.feedback-retraction.v1; immutable Registry
  collaboration/feedback-retraction-events/1, FULL/FULL_TRANSITIVE rules. Existing
  Debezium/Connect Outbox route, sole literal Connect writer, readonly Group validator.
  CDC cannot SELECT canonical/revision tables; no offset/slot/publication resets.
  Consumers deduplicate eventId, apply increasing marker versions, reauthorize audience
  and associate by feedbackId+feedbackVersion rather than treating later comments as
  retracted. Absence of a marker is not a knowledge verification/approval state.
- Existing secure singleton HTTPS Cookie, same-origin OWNER writes, no-store, probes,
  pool/trace metrics. Fixed-label brain.collaboration.feedback.retraction.reads/.changes;
  logs marker ID/state/trace only, no original comment, reason or credentials.

Space authorization precedes local tx. Concurrent revocation may race admitted work;
later calls recheck. UI renders literal text, keeps data/drafts only in memory and
aborts/clears on hide/unmount/denial. Unknown outcomes require fresh read; stale input
is preserved for copying and explicit refresh/reconfirmation. Status unknown is clearly
shown until a valid exact-version marker is loaded. Current/history each show their
own version-specific state; controls are collapsed by default.

## Validation

Run-BackendValidation.py --collaboration --group-kafka and --gateway;
Run-FeedbackRetractionContractValidation.py; Run-GraphWebValidation.py
--feedback-retractions with existing Group/Course/submission/feedback regressions.
Actual evidence: docs/feedback-retractions-validation.txt and
.local/feedback-retraction-validation.json.

Rubrics, attachments, notifications and prerequisite/automatic completion remain
separate features.

OWNER review queue and filters: [submission-review-queue.md](submission-review-queue.md).

Explicit OWNER grades: [submission-grades.md](submission-grades.md).

Study-wide own feedback overview: [study-my-feedback.md](study-my-feedback.md).
