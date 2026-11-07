# Private assignment resubmission requests

On /graph/groups, an OWNER opens a submitted answer and uses **재제출 요청** to
explicitly confirm the displayed answer/instruction versions and share a correction
reason with its author. Author reads the request under their own latest answer.
The ordinary **답안 재제출** action resolves a pending request upon a new accepted
attempt. Resolution records answer arrival, never grade, approval or completion.

## Decisions and compatibility

Collaboration owns the request workflow and its local Submission reference. Space
remains the fresh membership/role authority. Only current OWNER writes. Only author
and current OWNER read current/full history; peer MEMBER/VIEWER gets404 even for
absence. Current VIEWER authors read retained work; removed authors lose Group access,
while OWNER retains prior work under existing Submission policy. OWNER self requests
are permissible workflow comments and carry no knowledge verification meaning.

One stable request UUIDv7 per Submission, independent rrN version. REQUESTED may be
corrected, cancelled, or resolved by a newer submitted attempt. CANCELLED/RESOLVED
may be explicitly requested again against the latest reviewed answer. Exact current
same-reason/source REQUEST and same CANCELLED CANCEL are no-ops. Stale rrN still412.
Absence returns null/rr0 and no IDs/revisions/events. No destructive deletion.

REQUEST requires OPEN Assignment and nonarchived Study. CANCEL is permitted for a
pending request in OPEN/CLOSED Assignment and nonarchived Study. Retired parents are
read-only; completed Study is not an archive. Closing an Assignment does not remove
pending requests or reopen submission: its author still cannot submit. OWNER can
cancel the retained request. No new deadline, grading, prerequisite, notification,
private progress, public feed or private note-copy policy is introduced.

Request pins the current immutable Submission version and its original instruction
version, even when Assignment metadata has subsequently changed. New answers do not
rewrite that basis. RESOLVED also pins the immutable new answer version, records the
submitting author as changedBy and retains requestedBy. Exact old submission retries
return before resolution and cannot resolve a request created after that attempt.

## Transactions and contracts

V10 adds current/full immutable request revisions with local parent/source/resolution
FKs and newer-resolution check. Prior applied migrations/published Protobuf unchanged.
Group -> Study -> Assignment -> Submission locks are shared with all parent lifecycle,
feedback and submission writes. New answer/revision/Outbox plus request resolution/
revision/Outbox commit in one Collaboration ACID transaction; Outbox failure rolls
all of them back. No network calls while locked, cross-service SQL/FK, new dependency,
dataset, service or distributed transaction. Fresh Space authority precedes local tx;
revocation can race an admitted operation, subsequent requests check again.

- contracts/openapi/resubmission-requests.yaml: GET/PUT .../resubmission-request and
  GET .../resubmission-request/revisions. Exact PUT REQUEST reason/source or CANCEL.
- Strong If-Match rrN: missing428, malformed400, stale412, source/lifecycle409.
- Strict duplicate-free/trailing-free/unknown-field-free JSON; body<=20480 bytes,
  nonblank reason<=8192 UTF8 bytes, valid Unicode/no NUL/unpaired surrogates.
- Current/history author/current OWNER only, bounded queries<=256, history5/max10.
- contracts/events/src/main/protobuf/collaboration_resubmission_request_v1.proto.
  Full snapshots: ResubmissionRequested, ResubmissionRequestCancelled and
  ResubmissionRequestResolved. Topic collaboration.resubmission-request.v1, Registry
  collaboration/resubmission-request-events/1 with FULL/FULL_TRANSITIVE rules.
  Existing Outbox -> Debezium/Connect is sole topic producer; Group validator readonly.
  Consumers deduplicate stable eventId, apply increasing versions and reauthorize.
  CDC cannot SELECT canonical/revision tables; no offsets/slots/publications reset.
- Existing HTTPS singleton secure Cookie, same-origin writes, no-store, probes,
  pool/trace instrumentation. Fixed-label brain.collaboration.resubmission.request
  .reads/.changes counters; logs contain request ID/trace only, not reason/credentials.

UI keeps results/drafts only in memory, clears/aborts on hidden/unmount/denial.
Lost response requires a fresh read before explicit resubmission of a command.
Stale/source conflict preserves draft for copying and requires refresh/reconfirmation.
The latest answer must be reopened after source mismatch. All reason text is literal.
History shows each independent state change and its answer versions, paginated5.

Validation commands: Run-BackendValidation.py --collaboration --group-kafka, --gateway,
Run-ResubmissionRequestContractValidation.py, Run-GraphWebValidation.py
--resubmission-requests with existing Group/Course/sharing/feedback scenarios.
Actual local evidence is recorded in resubmission-requests-validation.txt and
.local/resubmission-request-validation.json.

Rubrics, attachments, notifications and prerequisite/
automatic completion remain separate features.

Original instructions per submitted attempt: [submission-instructions.md](submission-instructions.md).

Version-specific retraction/restoration: [feedback-retractions.md](feedback-retractions.md).

OWNER review queue and filters: [submission-review-queue.md](submission-review-queue.md).

Explicit OWNER grades: [submission-grades.md](submission-grades.md).

Study-wide own request overview: [study-my-resubmissions.md](study-my-resubmissions.md).
