# Private submission grades

Decision2026-10-24: current Group OWNER assigns integer0..100 points to another
author's submitted answer. Self-grading is forbidden. The explicitly selected
immutable submission/instruction versions are recorded; only that author and current
OWNER can read score, reason and history. Personal learning progress never changes
automatically. This follows the user's selected initial grading policy.

On `/graph/groups`, open Study -> Assignment -> submission review entry. The new
제출물 채점 section shows score and reason, original submission/instruction versions,
grader and timestamp. Author sees it below 내 최신 제출, without an editor. OWNER's
own answer has a clear 자기 제출은 채점할 수 없습니다 message and no grading form.

## Meaning and authorization

Score0 is an actual result; only null means ungraded. There is no implicit pass/fail
threshold, verification, acceptance, Course completion, progress sharing consent,
notification or leaderboard. Saving explicitly shares the score/reason immediately
with the submitted-answer audience AUTHOR_AND_OWNER, not all Group members.
OWNER confirms the displayed answer and original instruction versions and audience.
Reasons are literal text<=8192 UTF8 bytes, nonblank valid Unicode without NUL or
unpaired surrogates. API body<=20480 bytes, exactly score/reason/submissionVersion/
assignmentVersion; integer score0..100, canonical positive int64 source versions.

Fresh Space membership/role is checked for every request. Current OWNER writes,
with an additional local no-self-grading rule (including exact no-op). Author/current
OWNER read; other MEMBER/VIEWER receive404 even when no grade exists. A current
VIEWER who authored the answer retains reads; removed authors lose access. Current
OWNER retains submitted work under existing Submission policy. No actor selectors.
Fresh authorization occurs before the local transaction; concurrent revocation can
race an admitted operation, subsequent requests recheck. No distributed revocation
transaction is claimed.

## Source, audit and recovery

One stable grade UUIDv7 per Submission, independently versioned. GET before first
grade returns null/sg0 and allocates nothing. PUT uses strong `If-Match: "sgN"`.
Stale grade412, changed reviewed answer/original instruction basis409. Exact current
score+reason+source no-op produces no revision/event; stale same-value still fails.
Changing only the score produces a new full revision. No destructive deletion.

Grade pins the latest submitted attempt and the Assignment version originally
submitted, even if current instructions changed later. Resubmission never rebinds
the old grade. UI labels it as an earlier-answer result; OWNER must reopen the new
answer and explicitly grade it again. History retains earlier scores/reasons/source.
Feedback, its retraction markers and resubmission requests remain independent and
unchanged by grading. Submission/Assignment/Study/private Progress are not mutated.

OPEN/CLOSED Assignment + nonarchived Study permits grading. DRAFT/RETIRED Assignment
or RETIRED Study is readonly, retaining authorized current/history access. COMPLETED
Study is not archive. Parent state never reopens automatically.

Current/full revision/Outbox commit in one Collaboration local transaction under
Group -> Study -> Assignment -> Submission locks, serializing concurrent grading,
resubmission and lifecycle changes. V12 adds submission_grade and its immutable
revision table. Local FKs bind exact submission/parent/author scope and immutable
attempt. DB score range and grader!=author constraints complement request checks.
No old migration or published Protobuf changes; no new dependency/dataset/service,
cross-service SQL/join/FK/write or distributed transaction.

UI validates exact shapes, IDs/scope, integer scores, grader!=author, source ranges,
timestamps, source alignment and history ordering before display. No persistent
browser storage. Hide/unmount/denial clears and aborts private state. Unknown write
outcome/stale source/version preserves in-memory score/reason draft for copying;
fresh explicit read is required before retry, no automatic resubmission. Reopening
the new answer is required before grading a changed source. Existing HTTPS singleton
Cookie, same-origin writes, no-store/CSP, probes/pool/trace remain; fixed-label
brain.collaboration.submission.grade.reads/changes, ID+trace-only logs.

## Contracts and operation

- `contracts/openapi/submission-grades.yaml`: exact GET/PUT and /revisions.
- `contracts/events/src/main/protobuf/collaboration_submission_grade_v1.proto`.
- Topic collaboration.submission-grade.v1, Registry collaboration/submission-grade-events/1,
  immutable SHA256 d8474f3e1d01f23a9d6ae12517d21fc2bc6387cb0cd7b4cd0312cdfb565af8ca.
- FULL validity and FULL_TRANSITIVE compatibility; full score/reason snapshots.
  Consumers deduplicate eventId, apply increasing grade versions and authorize
  disclosure. No new consumer/projector in this slice.
- Debezium/Connect is sole literal topic writer; Group validator readonly. CDC can
  SELECT Outbox but cannot SELECT canonical/revision grade tables. No offset/slot/
  publication reset. No application Kafka producer.
- `scripts/Run-SubmissionGradeContractValidation.py`, backend --collaboration
  --group-kafka and --gateway; browser --submission-grades plus Group regressions.
- Actual validation/deployment evidence: docs/submission-grade-validation.txt and
  .local/submission-grade-validation.json.

Notifications and prerequisite/automatic
completion policy remain future features. Existing OWNER metadata review queue
continues describing feedback/request state and carries no scores/reasons.

Version-specific grade retraction/restoration: [grade-retractions.md](grade-retractions.md).

Shared rubric definitions and draft-only freeze policy: [assignment-rubrics.md](assignment-rubrics.md).

Explicit direct/calculated mode selection and immutable per-grade basis: [rubric-grading.md](rubric-grading.md).

Newly uploaded, author/current-OWNER-only attempt attachments: [submission-files.md](submission-files.md).
