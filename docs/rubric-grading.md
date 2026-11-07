# Direct and rubric-summed grading

Decision approved2026-10-25: OWNER chooses direct0..100 scoring or the sum of the
Assignment's frozen evaluation criteria. The same submitted-version score/history
and no-self-grading policy apply to both. No automatic private Learning progress,
pass/fail policy, peer/public disclosure, notification or personal-content copying.

## Use

Open `/graph/groups` -> Group -> Study -> Assignment -> submission -> 제출물 채점.
채점 방식 selects 직접 채점 or 항목별 합산 채점. The latter requires a nonempty
rubric defined before OPEN. Fill every criterion with an integer0..its maximum,
optional comments and an overall reason, confirm the reviewed source/audience and
save. The server computes total; partial/missing/foreign/reordered criteria and
points above a criterion cap are rejected. All-zero is a real0-point grade.

Current result shows immutable scoring basis. Each grade-history entry has
이 채점의 항목별 근거 조회 to fetch its exact basis; manual versions explicitly
have none. Scores/comments and historical bases are visible only to the submitted
answer author and current OWNER. Current VIEWER authors retain reads, removal loses
Group access, OWNER retains prior submitted work. Self grading is forbidden even
after ownership changes and for no-op/mode conversion.

## Modes, source and history

Existing /grade input/output,16-field Grade and published V1 Protobuf stay intact.
Manual UI keeps that endpoint for existing manual versions. New /rubric-grade
accepts explicit DIRECT/RUBRIC commands with the same strong sgN; it is not a second
independent score. Conversion of a calculated grade to DIRECT at unchanged score
and reason still appends a new grade version with no calculation basis. Later
RUBRIC conversion appends another version. A changed distribution/comment at the
same total appends a grade revision, preserving the previous basis.

Exact same calculated criteria/reason/source remains a no-op; stale sgN412 even
for the same values. Legacy unchanged /grade PUT remains a no-op and does not
implicitly switch modes or restore a retracted grade. Retraction/restoration stays
attached to each exact grade version; basis never disappears and a new revision
starts without a marker. Showing original total/basis never overrides the marker.

Assessment pins full frozen rubric metadata/criteria and ordered scores/comments,
grader/author, grade version, immutable submitted answer and original instruction
versions. Later root Assignment metadata corrections do not rewrite old evidence.
Resubmission leaves old grades/bases on the older attempt; fresh source/review and
explicit save are required. The form does not prefill older-attempt criterion
scores as a new review. Archive retains reads but freezes grading; CLOSED permits
grading, Study COMPLETED is not archive. Empty/absent rubric keeps direct grading.

## Contracts, storage and recovery

- GET/PUT .../submissions/{submissionId}/rubric-grade: six-field context/result;
  grade, assessment and rubric may be null. canWrite describes OWNER/lifecycle;
  RUBRIC additionally requires the nonempty frozen rubric.
- GET .../grade/revisions/{gradeVersion}/rubric-assessment returns exact one-field
  {assessment:null|snapshot}; unavailable grade404, authorized manual grade has null.
- DIRECT input: mode,submissionVersion,assignmentVersion,score,reason.
- RUBRIC input: mode,submissionVersion,assignmentVersion,rubricVersion,reason,criteria;
  each criterionId,score,comment. All input shapes exact/duplicate-free; no actor,
  Space, total-score or force-revision selector in calculated mode.
- Strong sgN required428, malformed400, stale412, source/rubric/lifecycle409,
  invalid content type415, body>32768 bytes413. Reason nonblank<=8192 UTF8 bytes,
  comment<=2048 bytes; valid Unicode/no NUL/unpaired surrogates. Canonical UUIDv7,
  positive int64 path versions; reads accept no query/body.

V15 adds only Collaboration-owned submission_rubric_assessment, immutable one
UUIDv7/version1 per exact grade version. Local FKs bind precise grade revision,
actor/submitted attempt, parent scope/author, rubric revision/Assignment and original
instruction revision. New exact-source UNIQUE constraints are applied in V15;
old migrations are not rewritten. Grade/current full revision, immutable assessment
and both Outboxes commit/rollback in one local DataSource/REQUIRED transaction with
Group -> Study -> Assignment -> Submission locks. No network under locks. Fresh
Space authority precedes the transaction; admitted concurrent revocation may race.

New collaboration_rubric_assessment_v1.proto, collaboration.rubric-assessment.v1,
Registry collaboration/rubric-assessment-events/1. Immutable SHA256
3377ec3b3a01105179cfb7d7fdf10eb9c0640787d5338f749384f4d1704fb6c6,
FULL validity/FULL_TRANSITIVE compatibility. RubricAssessmentRecorded contains the
full ordered basis and exact grade/source/actor/trace. Connect remains sole literal
writer; Group validator readonly. Old Grade/Rubric contracts stay byte-for-byte.
At-least-once consumers deduplicate eventId and authorize private disclosure; no
new score consumer or projection is introduced. CDC can SELECT Outbox but cannot
SELECT assessment/grade canonical tables. No offsets/slots/publications reset.

Existing HTTPS singleton Cookie, same-origin mutations, no-store/CSP, trace/probes/
pools retained. Metrics brain.collaboration.rubric.grading.reads (current/version)
and .changes (DIRECT/RUBRIC), logs ID/trace only, no scores/reasons/comments.
UI strictly validates exact scoped snapshots, full totals/caps, source/actor/grade
and basis association. Drafts are memory-only, cleared/aborted on hide/unmount/
denial. Stale/uncertain saves preserve copyable inputs, disable writes until explicit
fresh read, and never automatically retry. Unknown basis reads do not imply manual.

## Validation

Contracts: contracts/openapi/rubric-grading.yaml and the corrected path declarations
in submission-grades.yaml. Existing Grade schemas and runtime legacy route unchanged.
Backend --collaboration --group-kafka and --gateway; contract runner
scripts/Run-RubricAssessmentContractValidation.py; browser --rubric-grading with
affected Group grading/retraction/rubric/submission regressions.
Completed evidence: docs/rubric-grading-validation.txt and
.local/rubric-grading-validation.json after local Kubernetes/browser checks.

Notifications and prerequisite/automatic completion remain separate.
OWNER review queue retains its existing feedback/request metadata, without scores.

Newly uploaded, author/current-OWNER-only attempt attachments: [submission-files.md](submission-files.md).
