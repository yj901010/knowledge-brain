# Assignment submissions and immutable attempts

Registered participants explicitly type an answer in the selected assignment on
`/graph/groups`. The confirmation names the audience: the author and current Group
OWNER only. Other MEMBER/VIEWER accounts cannot enumerate or read another answer.
Space remains the fresh membership/role authority; Collaboration owns Submission
content, its author binding and attempt history. No architecture exception is needed.

## Rules

- Current OWNER and MEMBER submit their own answer to an OPEN assignment in a
  nonarchived Study. VIEWER cannot submit, but can read their previous answers.
- One Submission UUIDv7 per Assignment/author; each explicit resubmission adds an
  immutable attempt and advances only Submission version. Previous answers survive.
- Answers record the Assignment version observed and confirmed by the submitter.
  V5 foreign keys pin that local immutable assignment revision.
- DRAFT/CLOSED/RETIRED assignments and RETIRED Studies reject new attempts. Existing
  own history and OWNER review remain readable. COMPLETED Study is not an archive.
- OWNER may inspect all submitted answers/history, including a removed author's
  retained work; OWNER cannot overwrite another author's answer. Removed authors
  lose all Group access. Re-invited current authors may read their existing work.
- Text only in this slice; no draft persistence, withdrawal, grading,
  deadline automation, attachment, or automatic sharing of personal notes/evidence.

## Recovery and concurrency

POST requires `If-Match: "su0"` initially or `"suN"` for current own submission,
32-lowercase-hex Idempotency-Key, exact JSON content/assignmentVersion and same-origin
HTTPS. Keys are scoped to Assignment + author. Original content, Assignment version
and submission base version must match on retry. A new accepted attempt returns 201;
exact retry returns 200 and the original immutable attempt, even after later attempts,
close/archive or process restart. It never restores that attempt as the current one.
Current writer permission is checked even on replay. Changed original input is 409.

Stale submission or observed Assignment version is 412, missing precondition 428.
Group -> Study -> Assignment -> Submission row locks share the local Collaboration
transaction with both parent state changes and instruction edits. Outbox/revision
failure rolls back the current answer too. No network request is made while locked.

The UI refreshes mine after success/replay, preserves typed input on stale responses,
and offers a refresh of current instructions and own answer before fresh confirmation.
Refreshing explicitly discards the old request intent, not the typed input. Private
answers and drafts live only in memory; hidden-tab/unmount/denied access aborts and
clears them. Text is rendered literally. No browser persistent storage is used.

## APIs, events and operation

- [OpenAPI](../contracts/openapi/assignment-submissions.yaml)
- [Protobuf](../contracts/events/src/main/protobuf/collaboration_submission_v1.proto)
- `GET .../assignments/{assignmentId}/submissions/mine`: own current answer or null,
  plus point-in-time canSubmit; writes always recheck current permissions/state.
- `POST .../submissions`: submit/resubmit as authenticated actor; no author selector.
- `GET .../submissions`: OWNER-only bounded metadata catalog without answer content.
- `GET .../submissions/{submissionId}` and `GET .../{submissionId}/revisions`:
  author/current OWNER only; other members receive 404.

V5 adds assignment_submission and assignment_submission_revision with only local FKs.
All prior migrations/Protobuf remain unchanged. Events AssignmentSubmitted and
AssignmentResubmitted carry full explicit-answer snapshots with AUTHOR_AND_OWNER
audience. Existing Connect/Outbox publishes collaboration.submission.v1; no direct
app producer or new feed projector. Downstream consumers must preserve this audience,
deduplicate stable eventId and apply increasing aggregate versions. Registry
collaboration/submission-events/1 is immutable with FULL/FULL_TRANSITIVE rules.
Only Connect has the new literal producer ACL; Group validator has read-only access.
CDC credentials cannot SELECT either new canonical table. No offsets reset.

Bodies max65536 bytes; content nonblank valid Unicode/no NUL <=32768 UTF-8 bytes.
Strict JSON duplicate/trailing/extra-field rejection, exact routes, bounded keyset
catalog20/max100 and history5/max10, secure singleton Cookie, same-origin writes,
no-store responses, existing pool/probes and fixed submission read/change metrics.
Logs contain action, submission ID and trace only, never answer or credentials.

Tests and deployed validation evidence: `.local/submission-validation.json` and
`assignment-submissions-validation.txt`. Use backend `--collaboration --group-kafka`,
Gateway validation and browser `--submissions` with Group regressions.

Version-pinned OWNER feedback with author/owner-only immutable history is now available: [submission-feedback.md](submission-feedback.md).

Version-pinned private resubmission requests: [resubmission-requests.md](resubmission-requests.md).

Original instructions per submitted attempt: [submission-instructions.md](submission-instructions.md).

OWNER review queue and filters: [submission-review-queue.md](submission-review-queue.md).

Newly uploaded, author/current-OWNER-only attempt attachments: [submission-files.md](submission-files.md).

Study-wide own-submission overview for current OWNER/MEMBER/VIEWER: [study-my-submissions.md](study-my-submissions.md).

Study-wide own assignment/submission-presence overview: [study-my-assignments.md](study-my-assignments.md).
