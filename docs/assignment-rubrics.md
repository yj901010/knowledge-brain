# Assignment rubric definitions

Current Group OWNER may define optional shared evaluation criteria while an Assignment
is DRAFT. This policy was approved by the user on2026-10-24: criteria are editable
only in draft and permanently fixed once submissions open. Each nonempty rubric
has1..10 ordered criteria with integer points totaling100. Existing private0..100
manual grading remains independent; scoring is implemented in the separate explicit grading workflow. The existing no-self-grading rule is unchanged.

## Use and visibility

On `/graph/groups`, open Group -> Study -> Assignment -> 과제 평가 기준.
OWNER adds criterion names, descriptions and points, optionally reorders/removes
items, confirms the Group-shared audience and saves. Empty criteria explicitly
clear the optional rubric in DRAFT. Every change keeps a full immutable history.
평가 기준 변경 이력 조회 loads descending pages, default5/max10.

Current MEMBER/VIEWER can read current/history even while DRAFT, matching existing
Assignment visibility. Guests and nonparticipants cannot read. Membership removal
revokes later reads; the current OWNER manages definitions using fresh Space roles.
There is no personal-to-Group/public copy, private Note disclosure, peer grade
disclosure, notification or automatic private Learning progress change.

Once Assignment is OPEN, CLOSED or RETIRED, every rubric write is rejected, including
no-op, clear and initial creation for an Assignment opened without criteria.
An Assignment cannot return to DRAFT. Study RETIRED also freezes draft criteria;
COMPLETED is not archive. Existing Assignment metadata corrections remain possible
under their prior policy; they do not change the frozen rubric. Original submitted
instruction and manual grade snapshots/JSON/events remain unchanged.

## Versions and errors

GET before first definition returns null with strong ETag rb0 and allocates no ID,
revision or event. Initial empty PUT is also allocation-free. First definition
allocates stable rubric/criterion UUIDv7; edits and reordering keep criterion IDs.
New criteria send criterionId:null. Duplicate IDs400; IDs from another rubric or
removed from the current definition409. Removed IDs cannot be resurrected.
Clear retains the rubric ID, increments its independent version, records an empty
full snapshot and emits AssignmentRubricCleared.

PUT requires strong If-Match rbN and exact JSON
`{assignmentVersion, criteria:[{criterionId,title,description,maxPoints}]}`.
Source assignmentVersion pins the exact immutable definition at edit time and is
checked under the same locks as lifecycle changes. Parent mismatch409 requires
reopening/reconfirming the latest Assignment. Reconfirming unchanged criteria after
a parent edit records a new source pin; exact same source+criteria is a no-op.
Stale CAS412 even for same values. Rubric edits do not change Assignment version.

Titles are nonblank<=100 Unicode codepoints; descriptions<=2048 UTF8 bytes, blank
allowed. Valid Unicode/no NUL/unpaired surrogate; integer points1..100 with total100,
max10 items. Exact JSON rejects duplicate/unknown fields, trailing content, floats;
body<=32768 bytes, application/json. Strong CAS required428; malformed fields400,
wrong content type415, oversized413. History uses canonical positive int64 before
cursor and limit1..10 with duplicate-free bounded query. Unavailable scope404;
unavailable authority/storage503. No actor/role/Space selector in the command.

## Architecture and operations

V14 adds assignment_rubric and assignment_rubric_revision only in Collaboration.
Ordered criterion data resides in bounded full snapshots; local FKs bind exact
Assignment scope and immutable definition version. Current/revision/Outbox share
one local ACID transaction. Group -> Study -> Assignment locks serialize edits
against opening, parent edits and archiving, including concurrent first definitions.
No network call under the locks, cross-service SQL/join/FK/write, distributed
transaction, new service/dependency/dataset or old migration/Protobuf rewrite.
Fresh Space authorization precedes the transaction; concurrent revocation may race
an admitted request, and later requests recheck current authority.

New immutable Protobuf collaboration_assignment_rubric_v1.proto, topic
collaboration.assignment-rubric.v1 and Registry collaboration/assignment-rubric-events/1.
SHA256 724ade195e144d27e3ae73cafeaa013e9e97bd64b3107ba823ea5e264babf1f1,
FULL validity/FULL_TRANSITIVE compatibility. AssignmentRubricDefined/Revised/Cleared
carry complete ordered snapshots, exact source version, eventId/actor/trace/schema
headers. Debezium/Connect remains the sole literal-topic writer; the Group validator
is readonly. Consumers must deduplicate eventId and apply increasing rubric versions
and authorize Group membership before disclosure. No new consumer is introduced.
CDC can SELECT Outbox but cannot SELECT canonical rubric/current revision tables.
No offsets/slots/publications reset and no application Kafka producer.

Existing HTTPS singleton Cookie, same-origin mutation, no-store/CSP, trace/probes/pool
remain. Fixed-label metrics brain.collaboration.assignment.rubric.reads (current/history)
and brain.collaboration.assignment.rubric.changes; logs ID/trace only, never criteria
text/answers/credentials. UI strictly validates exact shapes, source/scope, ordering,
IDs/versions/text/point totals before display. Drafts live only in memory and clear/
abort on hide/unmount/denial. Stale or uncertain writes retain draft text for copying
and disable mutations until explicit refresh; no automatic write retries.

## Validation

- contracts/openapi/assignment-rubrics.yaml: GET/PUT rubric and GET revisions.
- scripts/Run-AssignmentRubricContractValidation.py; backend --collaboration
  --group-kafka and --gateway; browser --assignment-rubrics plus Group regressions.
- Integration checks cover locks, CAS, full audit/Protobuf, scope/local FKs, Outbox
  rollback, ordered clear/redefinition and real CDC replay. Browser exercises actual
  registered OWNER/MEMBER/VIEWER/outsider plus guest, freezes, lost replies, stale
  drafts/history/source refresh and unchanged manual grading/private Note.
- Completed evidence is recorded in docs/assignment-rubric-validation.txt and
  .local/assignment-rubric-validation.json after rollout and browser validation.

Attachments, notifications and automatic/prerequisite
completion remain separate features. Review queue continues to expose only its
existing feedback/request metadata; no rubric score or private grade fields.

Local validation observed PostgreSQL liveness exec checks timing out after1s and
restarting the database. Probe timings are now configurable, with prior defaults
preserved and local-only5s timeouts/liveness failure threshold6. Helm lint/render
and rollout preserve the same image/PVC/volume; no database/cluster reset.

Explicit direct/calculated mode selection and immutable per-grade basis: [rubric-grading.md](rubric-grading.md).
