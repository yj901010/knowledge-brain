# Study assignments

The selected Study on `/graph/groups` now contains Group-private assignments.
Collaboration owns the definition, lifecycle, revisions and transactional Outbox;
Space remains the sole membership/permission authority. Personal Planning tasks,
Learning sessions/progress/evidence, Course, Discussion and Submission stay separate.
No new dependency, external dataset, service/database or architecture exception is introduced.

## Behavior and permissions

- A registered current OWNER creates and edits title/instructions, opens, closes and archives.
- Current MEMBER and VIEWER read catalog, detail and immutable history, including DRAFT.
  DRAFT is preliminary shared Group content, not an owner-only unpublished document.
- `DRAFT -> OPEN -> CLOSED`; `RETIRED` archives from any state. No reopening.
  Metadata can be corrected after closure; archived assignments are immutable.
- Study COMPLETED still permits assignment management; Study RETIRED freezes new
  assignments and actual changes. Old definitions/history remain readable.
- Closing an assignment does not complete a member's learning or create submissions.
- No due dates, individual assignees, submissions, grading, reminders or private
  note/evidence/media relationships are implied by this definition slice.

## Consistency, recovery and privacy

Creation requires a 32-lowercase-hex Idempotency-Key scoped to Study + creator.
Immutable original title/description are compared; exact retry returns the current
snapshot (201 initially, 200 retry), including after edits and child/parent archive.
Changed original input under the same key returns 409. No retry restores old state.
Edits/state/archive require strong `"asN"` If-Match (428 missing, 412 stale).
Study `"stN"` does not change when an assignment changes. Exact current-version
state retry is a no-op even after parent archive; other writes to archived parent
return 409. Uncertain modification replies require refreshing the current version.

Every write locks the ACTIVE Group, then parent Study, then Assignment within the
same Collaboration transaction. Parent archive uses those same Group/Study locks.
Snapshot, revision and Protobuf Outbox insert commit or roll back together. All FKs
are local to Collaboration; Space and actor IDs remain immutable external refs.
Reads and writes obtain fresh Space authorization; tenant/resource IDs never grant
access by themselves. Guest, outsiders, removed members and wrong parent IDs fail.

Only secure singleton browser sessions are accepted. Writes require the configured
single Origin and same-origin Fetch Metadata. JSON has strict fields, duplicate and
trailing-token rejection; max body 65536 bytes, title 200 Unicode code points,
description 32768 UTF-8 bytes. Lists use bounded UUIDv7 keysets (20/100) and descending
history (5/10). UI validates scoped responses and renders instructions literally.
Private browser state stays in memory and clears/aborts on hidden tab, unmount or
access denial. Responses use no-store; logs contain IDs/trace/type, never instructions
or credentials. Metrics use fixed read/change type labels and the existing DB pool/probes.

## API and events

- [OpenAPI](../contracts/openapi/study-assignments.yaml)
- [Protobuf](../contracts/events/src/main/protobuf/collaboration_assignment_v1.proto)
- `GET/POST /api/groups/{groupId}/studies/{studyId}/assignments`
- `GET/PUT/DELETE .../assignments/{assignmentId}`
- `PUT .../assignments/{assignmentId}/state` and `GET .../revisions`

V4 adds study_assignment and study_assignment_revision; applied V1–V3 stay unchanged.
Events: AssignmentCreated, AssignmentRevised, AssignmentStateChanged, AssignmentRetired.
Full snapshots exclude all personal records/submissions. Connect's existing
`collaboration.${routedByValue}.v1` router emits `collaboration.assignment.v1` with
stable eventId and trace/schema headers. Registry `collaboration/assignment-events/1`
is immutable with FULL validity/FULL_TRANSITIVE compatibility. Only Connect writes;
the scoped Group validator reads. No direct application Kafka producer or offsets reset.
Consumers must deduplicate eventId and apply increasing aggregate versions.

## Verification

Run the existing backend validator with `--collaboration --group-kafka`, Gateway
validation and the browser validator with `--group-assignments` plus Group regressions.
Prepare the declared topic/ACLs and Registry contract before canonical test writes.
Validation evidence is recorded in `.local/assignment-validation.json` and
`study-assignments-validation.txt` after actual Kubernetes checks.

Study-wide own assignment/submission-presence overview: [study-my-assignments.md](study-my-assignments.md).
