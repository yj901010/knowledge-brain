# Grade retraction and restoration

Current Group OWNER can explicitly retract or restore a specific immutable grade
version with a nonblank reason. This extends the existing no-self-grading rule to
annotations: a submitted answer's author cannot annotate their own grade, even if
they are now OWNER. Fresh Space participation/role remains authoritative.

On `/graph/groups`, open Study -> Assignment -> submission -> 제출물 채점.
채점 철회·복원 shows the exact grade version and current annotation. OWNER opens
철회·복원 관리 열기 and explicitly confirms version/audience before saving.
Every historical grade has its independent marker and annotation history too.

## Score meaning and privacy

Retraction does not erase the score, grading reason or audit. Original values remain
visible to the submitted answer author and current OWNER, labeled 원본 점수 with
the separate marker. A retracted score is not a currently valid score. Score0 is
still a real original grade; absence of an annotation is not a grade of0 or ungraded
null. Unknown/failed marker reads explicitly show unknown state and disable writes.
No aggregate score/report/progress consumer is introduced in this slice. Any future
consumer that uses scores must resolve the marker for the exact grade version and
must never treat unknown marker state as valid.

Other MEMBER/VIEWER users get404 even before any marker. The author as a current
VIEWER retains reads; removed authors lose Group access, OWNER retains submitted
work under existing policy. No actor selector, all-member/public feed, automatic
personal-content publication, notification or change to private Learning progress.
Original grade GET/PUT/history JSON and published V1 event bytes remain unchanged.

## Version and lifecycle

One optional stable UUIDv7 per immutable grade version. Initial GET returns null/gr0
without creating ID/revision/Outbox. First RESTORED is409. RETRACTED/RESTORED reasons
append immutable full snapshots; current exact state+reason no-op unchanged, stale
same-value412. Strong If-Match grN, separate from grade sgN and feedback frN.
Reason<=8192 UTF8 bytes, valid nonblank Unicode/no NUL/unpaired surrogate, exact
state/reason JSON<=20480 bytes, canonical positive int64 path gradeVersion,
bounded duplicate-free query<=256 bytes, history5/max10 descending before cursor.

New grade versions do not inherit earlier markers. Retraction/restoration of a
historical grade does not affect the latest grade. Unchanged original grade PUT
does not implicitly restore a retracted grade; explicit marker restoration is
required. Resubmission leaves grades and annotations on their original immutable
attempt/instruction versions. Grade/submission/feedback/request/parent states are
not mutated by annotations. OPEN/CLOSED Assignment + nonarchived Study permits
changes; DRAFT/RETIRED Assignment or RETIRED Study readonly with retained history.
COMPLETED Study is not archive. Self annotation denied including no-op/restoration.

## Architecture and recovery

V13 adds only grade_retraction and grade_retraction_revision in Collaboration.
Local FKs bind exact immutable grade revision, Submission, parent scope and author;
current/revision actor!=author checks enforce no self annotation at storage too.
Current/full revision/Outbox share one local ACID transaction with Group -> Study ->
Assignment -> Submission lock order. No network call while locked. Exact grade
source lookup joins only local Collaboration tables. No cross-service query/FK/
write or distributed transaction, dependency/dataset/service or existing migration/
published Protobuf rewrite. Original immutable score snapshots remain canonical.

Fresh Space authorization precedes local transaction; concurrent revocation can
race an admitted request, later calls recheck. Existing HTTPS singleton Cookie,
same-origin writes, no-store/CSP, traces/probes/pool remain. Fixed-label counters
brain.collaboration.grade.retraction.reads/changes; logs contain ID/state/trace only,
never scores/reasons/answers/credentials.

UI validates exact view/marker/history shapes, scope/source IDs/versions, author and
nonself actor, enum/Unicode/timestamps and page ordering before display. Private
state is memory-only and clears/aborts on hide/unmount/denial. Uncertain writes and
stale CAS preserve the reason draft for copying, disable mutation until an explicit
fresh state read, and never automatically retry. Original score/reason is retained
with clearly unknown annotation state on marker read failure.

## Contracts and local validation

- contracts/openapi/grade-retractions.yaml: exact GET/PUT and /revisions under
  /submissions/{submissionId}/grade/revisions/{gradeVersion}/retraction.
- contracts/events/src/main/protobuf/collaboration_grade_retraction_v1.proto.
- Topic collaboration.grade-retraction.v1, Registry collaboration/grade-retraction-events/1,
  immutable SHA256 4ee5430aa79611d810d41d2c6389bd06ad91422f4df22902f6a8b7d2f1dfcdfc, FULL validity/FULL_TRANSITIVE compatibility.
- GradeRetracted/GradeRestored full snapshots; deduplicate eventId, apply increasing
  marker versions per exact grade revision and authorize before disclosure.
- Debezium/Connect sole literal producer, Group validator readonly. CDC Outbox SELECT
  allowed; canonical/revision annotation and grade SELECT denied. No offsets/slots/
  publication reset, application producer or new read-model consumer.
- scripts/Run-GradeRetractionContractValidation.py; backend --collaboration
  --group-kafka and --gateway; browser --grade-retractions plus Group regressions.
- Actual evidence: docs/grade-retraction-validation.txt and .local/grade-retraction-validation.json.

Per-criterion rubric scoring, attachments, notifications and prerequisite/automatic completion remain
separate features. Existing metadata review queue continues describing feedback/
resubmission state and does not expose scores, reasons or new marker details.

Shared rubric definitions and draft-only freeze policy: [assignment-rubrics.md](assignment-rubrics.md).
