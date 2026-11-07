# Original instructions for submitted attempts

On /graph/groups, **이 제출의 원본 지시문 조회** opens the exact instructions
referenced by that answer. Available below the author's latest submission, the OWNER
review detail and each immutable attempt in submission history. It loads on demand,
shows both submission/instruction versions and a historical-state label, and can close.
The separate **새 제출 기준 최신 지시문** section describes the current next-submit
basis. Changing Assignment title/description, submitting again, closing or archiving
does not change instructions belonging to an old answer. History can show old/new
answers together with their respective original instructions.

## Privacy and compatibility

GET .../submissions/{submissionId}/revisions/{version}/instructions is exact/read-only.
Only the submission author/current Group OWNER can read, under the existing Submission
audience. Current VIEWER authors retain read access; peer MEMBER/VIEWER gets404 even
for a missing attempt; removed authors lose Group access and the UI clears. OWNER can
read retained work as under existing Submission policy. No author or Space selector.
Fresh Space authority checks Group/Space/current membership before the local read.
Concurrent revocation can race an admitted operation; subsequent calls recheck.

The response binds submissionId/submissionVersion/assignmentVersion plus the full
immutable Assignment snapshot. Historical state OPEN means it was open when submitted;
it confers no current submission permission. No fallback to latest instructions and no
large history scan. Existing V5 immutable attempt->Assignment revision FK is used.
One bounded SQL statement filters full Group/Space/Study/Assignment/Submission scope
and authenticated author (unless current OWNER), joining only Collaboration-owned
immutable revision tables. No cross-service query/FK, network while locked, database
change, new ID/revision/event/topic, dependency/dataset or architecture exception.
This read-only feature introduces no migration; existing canonical revisions are reused.

Exact canonical UUIDv7 and positive decimal signed-int64 path version. No GET body or
query accepted; malformed400, missing/unauthorized source404, fresh authority/storage
unavailable503. Unsupported routes/methods stay denied. Existing singleton HTTPS Cookie,
no-store, trace/pool/probes apply. Fixed existing counter brain.collaboration.submission
.reads has type=instructions; no title/description/answer is logged.

UI validates exact IDs, both versions and full instruction shape before display.
Literal text only, no raw HTML execution. Read failure or mismatched response clears
old displayed instructions and allows explicit retry. In-memory only, abort/clear on
hide/unmount/denial; new source version remounts the component. No persistent browser
storage/cache. Read-only functionality does not change answers, feedback, resubmission
request state, progress or personal source Notes.

## Validation

- contracts/openapi/submission-instructions.yaml.
- Run-BackendValidation.py --collaboration --group-kafka and --gateway.
- Run-GraphWebValidation.py --submission-instructions with existing Group/Course/
  feedback/resubmission/sharing regressions.
- docs/submission-instructions-validation.txt and .local/submission-instructions-validation.json
  record actual backend, browser, deployment/source, contract and operational evidence.

Rubrics, attachments, notifications and prerequisite/
automatic completion remain separate features.

Version-specific retraction/restoration: [feedback-retractions.md](feedback-retractions.md).

Explicit OWNER grades: [submission-grades.md](submission-grades.md).
