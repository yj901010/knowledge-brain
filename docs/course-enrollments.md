# Voluntary Course enrollment

The selected Course on `/graph/groups` now supports voluntary participation and
withdrawal. Collaboration owns this Course relationship. Space still owns all Group
membership/role/access, Learning still owns personal Progress/StudySession/Evidence.
There is no architecture exception, new dependency, dataset or service.

## Roles, privacy and lifecycle

- Current registered OWNER/MEMBER may enroll only themselves in an ACTIVE Course.
  Joining shows explicit UI consent that current Group OWNER can see the record.
- Current VIEWER reads their own past record/history and may withdraw it. They cannot
  join/rejoin. This self opt-out does not grant content-writing permission.
- Only the participant and current OWNER read each stored record/history. Peers get404.
  Only current OWNER lists minimal participation metadata; no owner editing on behalf
  of other principals. Owner enrollment is voluntary too, never implicit.
- The roster retains withdrawn and formerly authorized Group participants for audit.
  It describes enrollment records, not current Group permissions or learning completion.
  Removing a Group member immediately denies their Course/enrollment API access.
  A later Group re-invitation can reveal their retained record again; enrollment itself
  never grants Group access and removal does not silently rewrite domain history.
- ENROLLED <-> WITHDRAWN; new enrollment/rejoin requires ACTIVE Course. Withdrawal is
  allowed for any current Group role even after Course completion/retirement. No Course
  reopening, source Study transition, progress completion or Submission mutation.
- Personal Notes, progress, evidence and files remain private. Owner-only count summaries now require separate explicit consent; see
  [course-progress-sharing.md](course-progress-sharing.md). Completion rules remain subsequent work. Participant-only learning
  progress is described in [course-learning-progress.md](course-learning-progress.md).

## Concurrency and recovery

GET own enrollment returns a nonpersistent NOT_ENROLLED sentinel with version0,
null enrollmentId/timestamps and courseVersion0 before first enrollment. No ID,
row, revision or event is allocated by a read or absent withdrawal no-op.

PUT uses exact `{ "state": "ENROLLED" }` or WITHDRAWN with strong `"ceN"` If-Match,
including ce0 initially. Missing428, stale412, malformed/wrong aggregate tag400.
This independent version does not change Course coN or Study stN. Exact current-version
no-op emits no event, including an already ENROLLED record after Course archive.
VIEWER still cannot send ENROLLED. Old versions fail even for identical state.

There is one UUIDv7 enrollment per Course+principal. Rejoin keeps that ID and creation
timestamp, advances version, and pins current Course revision in immutable history.
Concurrent initial requests have one winner; old requests cannot restore a later
withdrawn record. After a lost response, read the latest own state; the UI requires
explicit refresh and new consent before a new join. It never automatically replays.

Writes lock ACTIVE Group, Course, then own Enrollment in one local transaction.
Course lifecycle uses the same parent locks. Snapshot, immutable revision, local
Course-revision provenance FK and Outbox commit/roll back together; no network call
inside the transaction or cross-service SQL. V8 only adds course_enrollment and
course_enrollment_revision; existing migrations and published contracts stay unchanged.

## Contracts and operation

- [OpenAPI](../contracts/openapi/course-enrollments.yaml)
- [Protobuf](../contracts/events/src/main/protobuf/collaboration_course_enrollment_v1.proto)
- GET/PUT `/api/groups/{groupId}/courses/{courseId}/enrollment`
- GET `.../enrollment/revisions`
- GET `.../enrollments` (OWNER)
- GET `.../enrollments/{enrollmentId}` and `.../{enrollmentId}/revisions` (self/OWNER)

CourseEnrolled/CourseWithdrawn events use collaboration.course-enrollment.v1 and
immutable Registry collaboration/course-enrollment-events/1. Stable eventId and
increasing aggregate versions support at-least-once replay. Consumers must preserve
PARTICIPANT_AND_OWNER audience. Existing Collaboration Outbox/Debezium/Connect route
publishes; no app producer or new projector. Literal Connect writer and readonly Group
validator only, no existing consumer offsets/slots/publications reset. CDC cannot read
canonical Enrollment/Course tables; only Outbox is readable.

Fresh current role and Group/Space match on every request; singleton secure browser
cookie, HTTPS same-origin writes, no caller actor/tenancy fields. Exact JSON/body and
query bounds: request65536 bytes, catalog20/max100, compact history5/max10. The existing
full Course snapshot history remains3/max5. No-store responses, bounded Gateway
transport, UUIDv7 keysets, descending revisions. UI state memory-only, clears/aborts on
hide/unmount/denial, literal IDs/text and explicit error recovery. Existing pool/probes
and HTTP/event trace propagation, fixed read/change metric labels, body-free logs.

Run backend `--collaboration --group-kafka`, Gateway suite, browser `--enrollments`
with Group regressions. Results are `.local/enrollment-validation.json` and
`course-enrollments-validation.txt` after actual local Kubernetes verification.

Participant-only Course learning progress is now described in [course-learning-progress.md](course-learning-progress.md). Explicit owner-only count sharing is described in [course-progress-sharing.md](course-progress-sharing.md). Prerequisite/completion policies remain subsequent work.
