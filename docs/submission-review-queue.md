# OWNER submission review metadata queue

The existing **소유자 제출 목록** on /graph/groups now shows **제출 검토 목록**,
with an explicit filter and **검토 목록 새로고침**. Opening an entry still fetches
the current authorized answer and existing feedback/request controls. Status metadata
never substitutes for opening the answer before writing version-pinned feedback.

## Filters and meaning

- ALL: all submitted work, ascending stable Submission UUIDv7.
- NEEDS_FEEDBACK: no feedback, latest feedback targets an older answer, or latest
  feedback version has an explicit RETRACTED annotation.
- HAS_FEEDBACK: complement; latest feedback targets current answer and is not retracted.
  This is an opinion-presence flag, not a grade, acceptance, verification or completion.
- RETRACTED_FEEDBACK: latest comment version is retracted, including old-answer comments.
  A historical retraction on an earlier comment version never marks a newer comment.
- PENDING_RESUBMISSION: existing request is REQUESTED, independently of feedback status.
  Closed/archived assignments may retain requests; queue does not reopen submission.

Each row shows author/Submission/instruction versions, latest feedback/source versions,
its own retraction marker state, needsFeedback, and request/source/version metadata.
Original answer, comment, reason, instruction and personal Note content are excluded.
Only submitted work appears; this does not enumerate members who have not submitted.

## Authorization and consistency

Only current registered Group OWNER reads. MEMBER/VIEWER always403, even for empty
filters; removed participants lose Group access, while OWNER retains already submitted
work under existing policy. Space freshly authorizes Group/Space/role before each read.
Concurrent revocation may race admitted requests, subsequent reads recheck. No actor
selector. Existing assignment/Study close/archive preserves reads, not write eligibility.

Collaboration owns Submission, Feedback, version-specific Retraction and Resubmission
Request. Existing local unique keys permit one bounded statement joining only their
metadata. Current answer and atomic request resolution are read in the same statement
snapshot. Full Group/Space/Study/Assignment scope is always applied. No cross-service
SQL/FK, new database/owner, distributed transaction or architectural decision change.
No canonical writes/IDs/revisions/Outbox events, migration, new event contract, dependency
or dataset. Existing catalog API remains unchanged; UI uses the new read-only endpoint.

Ascending UUIDv7 keyset default20/max100, predicates before LIMIT, limit+1 detects next.
Pages are current reads, not a frozen export: filter eligibility can change between
pages; refresh starts from beginning. No count/global roster query or unbounded history
scan. Latest feedback version is joined to its matching marker version, not any prior
annotation. Existing indexes/unique keys and statement timeout bound local work.

## API and UI

GET .../assignments/{assignmentId}/submissions/review-queue with exact optional
filter/after/limit. Contract: contracts/openapi/submission-review-queue.yaml.
Canonical UUIDv7 cursor, exact uppercase enum, limit1..100, no GET body, query<=256;
duplicate/unknown selectors/invalid values400. Unsupported methods/routes denied.
No-store despite metadata because current permissions can change. Existing singleton
secure Cookie, trace/pool/probes and fixed submission.reads type=review-queue metric.
Logs contain request IDs/trace/status only, no content or author-selected credentials.

UI uses metadata only, validates exact row shape, source ranges, state relationships
and requested filter before display. Invalid/error responses clear old queue/cursor
and offer explicit refresh. Changing filter resets page/selected detail; refresh changes
the list without overwriting an open feedback draft. Existing submission draft is
retained. In-memory only; hide/unmount/denial abort and clear private results. No polling,
automatic grading/completion, notifications or implicit personal-data publication.

## Validation

Run-BackendValidation.py --collaboration --group-kafka and --gateway;
Run-GraphWebValidation.py --submission-review-queue with existing Group/Course/
feedback/resubmission/sharing regressions. Actual local evidence is recorded in
docs/submission-review-queue-validation.txt and .local/submission-review-queue-validation.json.

Rubrics, attachments, notifications and prerequisite/automatic completion remain
separate features.

Explicit OWNER grades: [submission-grades.md](submission-grades.md).
