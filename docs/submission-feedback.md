# Private submission feedback

The next collaboration slice adds explicit OWNER text feedback to submitted answers.
On `/graph/groups`, the author sees feedback below their latest answer. The OWNER
opens an answer from the existing submission catalog to write or correct feedback.
The editor explicitly confirms the displayed submission/instruction versions and
author audience. Text is literal, not interpreted as HTML or executable content.

## Privacy and source provenance

- Space remains current Group membership/role authority. Current OWNER alone writes.
  The submission author and current OWNER alone read feedback and immutable history.
  Other MEMBER/VIEWER users get404 even when no feedback exists. No actor selector.
- A current VIEWER who authored the submission can read retained feedback. Removed
  authors lose Group access and the UI clears; current OWNER can review retained work,
  consistent with existing Submission visibility. Re-invitation restores authorized
  access, without recreating records. This is distinct from opt-in Course progress sharing.
- One stable feedback UUIDv7 per Submission, independently versioned from the answer.
  A read before any feedback returns null/sf0 without allocating an ID, revision or event.
- Every saved comment pins an immutable Submission revision and its original Assignment
  instruction version. Later instruction edits do not rewrite what the author submitted.
- Resubmission leaves old feedback unchanged and visibly labels it as an earlier-answer
  comment. OWNER must open/review the new answer and explicitly save feedback again.
  Reusing the same comment text on a new answer is a new revision, with new provenance.
- Text feedback does not assign a grade, approve knowledge, mark completion, trigger
  a resubmission state or change Assignment/Study/Course/private Progress. It is an
  opinion shared with the submission author; no public or Group-member feed is created.
  OWNER may comment on their own answer too; this carries no approval/verification.

## Lifecycle, concurrency and recovery

Feedback can be written after an Assignment closes: current OWNER + OPEN/CLOSED
Assignment + nonarchived Study. DRAFT/RETIRED Assignment or RETIRED Study is read-only;
current/history stay accessible to authorized author/OWNER. COMPLETED Study is not
an archive. There is no destructive feedback deletion or hidden overwrite of history.

PUT requires exact content/submissionVersion/assignmentVersion and independent strong
`If-Match: "sf0"` initially or `"sfN"` for existing feedback. Feedback changed gives412;
reviewed answer or submitted instruction provenance changed gives409. Exact current
no-op emits no new revision/event, but stale same-value requests still fail412.
Body<=20480 bytes, nonblank valid Unicode/no NUL/unpaired surrogate, content<=8192 UTF-8
bytes. Queries are exact, bounded<=256 bytes; history descending5/max10 with before.

Within Collaboration V9, feedback current/full revision/Outbox share a local transaction.
Local Group -> Study -> Assignment -> Submission locks serialize with resubmission,
instruction edits and parent archive. There is no network call while locked, new DB
owner, cross-service query/FK/join, new dependency/dataset or distributed transaction.
New FKs bind exact local submission/parent/author scope and immutable reviewed attempts.
No previously applied migration or published Protobuf is rewritten.

Fresh Space Group authorization happens before the local transaction. A concurrent
revocation can race an admitted operation; subsequent requests recheck permissions.
Existing trusted HTTPS singleton browser Cookie, same-origin writes, no-store, probes,
pool metrics and trace propagation remain. Fixed-label counters
`brain.collaboration.submission.feedback.reads` / `.changes`; logs contain feedback
ID and trace only, never answer/comment content or credentials.

The UI keeps drafts/results in memory only and clears/aborts on hide/unmount/denial.
Unknown write outcomes require a fresh feedback read, with no automatic retry.
Stale/source-conflict input stays available for copying before explicit refresh.
After source mismatch, the author answer must be reopened before writing. Independent
history shows the reviewed submission/instruction versions for every comment revision.

## Contracts and local operation

- `contracts/openapi/submission-feedback.yaml`: exact GET/PUT/history and responses.
- `contracts/events/src/main/protobuf/collaboration_submission_feedback_v1.proto`.
- Topic `collaboration.submission-feedback.v1`; Registry
  `collaboration/submission-feedback-events/1`, immutable SHA256
  `b0f6f6b3aba97c4a2db0468f527d35e0edd2d5bc24347bde9ada67b2b12af531`.
- Registry FULL validity/FULL_TRANSITIVE compatibility. Full feedback snapshots preserve
  AUTHOR_AND_OWNER; consumers deduplicate eventId, apply increasing feedback versions
  and re-authorize before disclosure. No new application producer or projector.
- Existing Debezium/Connect Outbox route publishes; Connect is the sole literal topic
  writer, Group validator readonly. CDC cannot SELECT feedback canonical/revision tables.
  No consumer offsets, slots or publications are reset.
- `scripts/Run-SubmissionFeedbackContractValidation.py` verifies immutable schema/rules.
  Backend checks: `Run-BackendValidation.py --collaboration --group-kafka` and --gateway.
  Browser checks: --submission-feedback plus existing Group/Course/sharing regressions.
- `docs/submission-feedback-validation.txt` and `.local/submission-feedback-validation.json`
  record actual integration, browser, source provenance, ACL and local deployment evidence.

Explicit resubmission requests are available in [resubmission-requests.md](resubmission-requests.md).

Rubrics, attachments,
notifications and prerequisite/automatic completion policies remain separate features.

Original instructions per submitted attempt: [submission-instructions.md](submission-instructions.md).

Version-specific retraction/restoration: [feedback-retractions.md](feedback-retractions.md).

OWNER review queue and filters: [submission-review-queue.md](submission-review-queue.md).

Explicit OWNER grades: [submission-grades.md](submission-grades.md).

Study-wide own feedback overview: [study-my-feedback.md](study-my-feedback.md).
