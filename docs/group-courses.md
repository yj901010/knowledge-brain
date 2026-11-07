# Group Courses and curriculum

`/graph/groups` now lets a current Group OWNER create a Course and order up to50
existing same-Group Studies. Current MEMBER and VIEWER can read Courses, curriculum
and full change history, and open each current Study. Space is the authority for
current roles on every request; Collaboration owns Courses and their relationships.
This preserves PROJECT.md service boundaries without an architecture exception.

## Scope and behavior

- Course lifecycle: DRAFT -> ACTIVE -> COMPLETED; RETIRED is irreversible and available
  from any nonarchived state. Metadata can be corrected until retirement.
- Only DRAFT curriculum may change. Select/reorder/remove existing nonarchived Studies
  in this Group/Space, with max50 unique IDs. Started curriculum is fixed.
- Start requires at least one Study and rechecks current source availability. Starting
  or completing a Course never starts/completes Studies or anyone's personal progress.
- Every selection pins Study ID, observed version and title. Course history preserves
  this provenance. Opening a Study reads its latest authorized content. Later source
  rename/archive never cascades into Course snapshots. A draft can remove a retired
  source; start with a retired source is409. A draft save can explicitly refresh the
  observed metadata/version of the same ordered IDs.
- One Study may occur in multiple Courses. No Study content/graph copy, implicit
  enrollment, private Note/evidence/attachment/Submission import or public sharing.
  Existing Study Assignment/Submission/Discussion permission rules still apply.
- This implements Course organization and curriculum. Enrollment and voluntary opt-out are now described in [course-enrollments.md](course-enrollments.md).
  Participant-only progress is available in [course-learning-progress.md](course-learning-progress.md); explicit owner-only count sharing is in [course-progress-sharing.md](course-progress-sharing.md). Prerequisite enforcement remains subsequent work.

## Correctness and security

Course creation uses a32hex key scoped Group+creator and immutable original input.
Exact retries return the current Course after edits/archive/restart; changed original
intent409, first create201, retry200. Every retry requires the current OWNER role.
Edits, curriculum replacement, state and archival use independent strong `"coN"`
If-Match: missing428, stale412. Exact current-version no-op emits no new revision or
event. Started/archived curriculum rejects writes, including identical submissions.

All writes lock ACTIVE Group, Course, then referenced Studies in UUID order, with no
network calls while locked. Existing Study writers lock that same Group first.
Course current snapshot, current ordered local relationship, immutable full revision
and Outbox commit or roll back together. V7 adds group_course,
group_course_revision and course_study; every FK is within Collaboration. Relationship
FKs pin the Study revision and same Group/Space. Earlier migrations remain unchanged.

Exact JSON/query validation, singleton secure browser cookie, HTTPS same-origin writes,
fresh current role, no caller-supplied actor/tenancy, bounded request65536 bytes.
Titles<=200 codepoints/no controls, description<=32768 UTF-8 bytes/no NUL. Catalog
metadata20/max100; full snapshots history3/max5 preserve Gateway's2MiB bound. UUIDv7
keysets and descending version history. Responses no-store; browser state memory-only
and cleared/aborted on hide/unmount/denial. Unknown creation responses retain original
key; stale edits require explicitly reopening current Course. All text is literal.

## Contracts and operation

- [OpenAPI](../contracts/openapi/group-courses.yaml)
- [Protobuf](../contracts/events/src/main/protobuf/collaboration_course_v1.proto)
- GET/POST `/api/groups/{groupId}/courses`
- GET/PUT/DELETE `.../courses/{courseId}`
- PUT `.../{courseId}/curriculum`, PUT `.../{courseId}/state`
- GET `.../{courseId}/revisions`

CourseCreated/Revised/CurriculumChanged/StateChanged/Retired full snapshots use
`collaboration.course.v1`, immutable Registry `collaboration/course-events/1`.
Existing Collaboration Outbox/Debezium/Connect routing publishes; no app producer or
new projector. Stable eventId supports at-least-once deduplication, increasing
aggregate version and Group-only authorization must be preserved by consumers.
Connect alone has the literal topic Write permission; the Group validator reads.
Canonical Course/Study tables remain unreadable to CDC credentials; Outbox readable.
No offsets, slots or publications reset. Existing DB pool/probes, trace propagation
and fixed-label Course read/change counters remain in use; logs omit user bodies.

Validate with backend `--collaboration --group-kafka`, Gateway suite, browser
`--courses` plus Group regressions. Results: `.local/course-validation.json` and
`group-courses-validation.txt` after actual local Kubernetes checks.

Participant-only Course learning progress is now described in [course-learning-progress.md](course-learning-progress.md). Explicit owner-only count sharing is described in [course-progress-sharing.md](course-progress-sharing.md). Prerequisite/completion policies remain subsequent work.

Course-wide own assignment/submission-presence overview: [course-my-assignments.md](course-my-assignments.md).

Course-wide own submission/grade overview: [course-my-submissions.md](course-my-submissions.md).
