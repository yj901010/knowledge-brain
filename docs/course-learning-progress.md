# Participant-only Course learning progress

The selected Course on `/graph/groups` now shows each Study's NOT_STARTED,
IN_PROGRESS or COMPLETED state, completion count/percentage, explicit corrections and
full change history. Course, Study and enrollment operational states stay independent.
These are self-reported learning statuses, not grades or enforced prerequisites.

## Ownership and access

Learning owns this Progress aggregate and its UUIDv7/revisions/Outbox. Collaboration
continues to own Course/curriculum/enrollment; Space remains Group membership/role
authority. All references crossing those services are immutable IDs, with no DB joins,
queries, FKs or distributed transaction. No architecture exception or dependency added.

Progress belongs to the source Group Space, partitioned by Course and authenticated
principal, and is PRIVATE/PARTICIPANT_ONLY. This Group-Space tenancy does not grant
OWNER or peers access to another participant's progress. Every endpoint addresses
`my-progress`; no caller-selected participant, owner roster, implicit sharing or
Personal Space copy. Explicit owner-only summary publication is a separate opt-in aggregate; see [course-progress-sharing.md](course-progress-sharing.md).

- Current registered Group participants read only their own retained progress/history.
- Actual writes require current OWNER/MEMBER, ENROLLED, Course ACTIVE or COMPLETED.
  Completed Course remains writable so each person's learning can finish independently.
- VIEWER, withdrawn enrollment and RETIRED Course are readonly. Progress survives
  withdrawal/rejoin with the same Learning ID and status history.
- Group removal denies subsequent context/progress/history requests and clears the UI.
  A later authorized re-invitation can reveal the retained own record again.
- Source Study IDs are immutable curriculum references. Study title/version labels
  come from the currently authorized Course context; Learning persists IDs/statuses
  and observed Course/enrollment versions, without Course/Study content or titles.
- No automatic Course/Study completion, Assignment/Submission mutation, personal
  Note/session/evidence linking, Activity projection or public publication.

## Authoritative context and consistency

Collaboration GET learning-context checks the current singleton browser credential
through the existing fresh Space participation authority. It reads Course and own
Enrollment consistently under Group -> Course -> Enrollment local locks. It returns
only authorized Course curriculum and the caller's role/enrollment; no Learning data.

Learning's fixed HTTPS Collaboration client delegates that exact browser cookie and
trace, without caller Authorization/identity headers or Origin. Trusted CA, no redirects,
connect3/read5 second limits, exact bounded JSON <=65536 bytes, valid UUIDv7/scopes,
versions/lifecycle/roles and <=50 unique curriculum entries. Denials propagate;
malformed/unavailable authority fails closed. No authorization cache is introduced.
Learning->Collaboration8080 is explicitly allowed by the service network policy.

Every progress read/write/history request obtains fresh context before reading or
starting a Learning transaction. This is authorization at the check point, not an
atomic transaction with Collaboration/Space. A revocation concurrent with an admitted
request can race that local commit; subsequent requests recheck and fail. No network
call while a Learning transaction is locked and no distributed locking/2PC is claimed.

PUT carries exact status, observed courseVersion and enrollmentVersion. Changed context
is409; independent strong `"cpN"` prevents lost progress updates (missing428, stale412,
cp0 initially). Updates address only a Study in the authoritative curriculum. Source
metadata edits require refresh even when order is unchanged. Started curriculum is
already fixed by Course rules; an incompatible stored layout fails409.

Reads and initial NOT_STARTED no-ops allocate no ID/row/revision/event. Actual writes
serialize per Space/Course/principal with a local transaction-scoped PostgreSQL advisory
lock, including the absent-row case. Hash collisions only serialize unrelated scopes.
Current snapshot/full immutable revision/Outbox commit or roll back together. Current
version no-ops emit nothing; stale identical choices still fail412. Corrections and
rejoin retain the same progress ID and full audit provenance. After an unknown response,
the UI requires explicit refresh; it never automatically repeats a status choice.

## APIs and operation

- [OpenAPI](../contracts/openapi/course-learning-progress.yaml)
- [Protobuf](../contracts/events/src/main/protobuf/learning_course_progress_v1.proto)
- Collaboration GET `/api/groups/{groupId}/courses/{courseId}/learning-context`
- Learning GET `.../my-progress` and `.../my-progress/revisions`
- Learning PUT `.../my-progress/studies/{studyId}`

Learning V11 adds only group_course_progress and group_course_progress_revision, with
local revision FK and source-ID references. Collaboration schema remainsV8. Previously
applied migrations/published Protobuf unchanged. Existing Learning Outbox/CDC emits full
snapshots on learning.course-progress.v1 using immutable learning/course-progress-events/1.
Consumers must preserve PRIVATE/PARTICIPANT_ONLY, deduplicate stable eventId and apply
increasing aggregate versions. Only Connect writes and Learning validator reads the
literal topic; no new projector, broad ACL, offset/slot/publication reset.

Singleton browser cookie, HTTPS same-origin writes, exact JSON/query, request4096 bytes,
curriculum max50, history5/max10 full compact snapshots fit bounded Gateway transport.
No-store responses, literal labels, memory-only state cleared/aborted on hide/unmount/
denial. Enrollment changes refresh the progress context. Existing pool/probes and HTTP/
event traces; fixed read/change metrics and body-free logs. CDC credentials cannot
SELECT canonical Progress/revision tables; Outbox remains readable.

Validate Learning `--learning --learning-kafka`, Collaboration `--collaboration --group-kafka`,
Gateway, and browser `--course-progress` plus all Group regressions. Results:
`.local/course-progress-validation.json`, `course-learning-progress-validation.txt`.
