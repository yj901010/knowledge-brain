# Study own-submission overview

On /graph/groups, open Group -> Study -> 스터디 과제 -> 내 제출 현황 열기. Current OWNER, MEMBER and VIEWER see only their own submitted work across that Study. No unsubmitted assignments or peer participation counts are inferred. OWNER also sees only own work here; the existing OWNER review/grading queues remain independent.

The twelve-field row contains submission/assignment IDs, current shared assignment title/state, latest submitted version and observed instruction version/time, and exact latest-grade-version metadata. No answer, score, reason, grader, rubric or file content is copied. ALL is default; MISSING, CURRENT, OUTDATED, RETRACTED, UNAVAILABLE and NEEDS_GRADING use the existing grading queue meanings. Zero in direct/rubric mode counts as a grade. CURRENT means recorded metadata on this submitted version, never pass/completion/verification. Unknown annotation remains UNAVAILABLE; historical annotations cannot invalidate newer grades.

GET /api/groups/{groupId}/studies/{studyId}/my-submissions accepts singleton filter/after/limit only, canonical UUIDv7, default20/max100. Registered secure singleton session and current Space Group participation precede local data access; no caller author selector. Group/Space/Study/actor predicates and full-tuple local Assignment join prevent foreign submissions. A single statement reads own Collaboration Assignment/Submission/Grade/Retraction metadata, filters before LIMIT and uses ascending submission UUID keyset/limit+1. Existing Study/Assignment catalog and Assignment/author unique indexes support the local read. No migration, mutation, audit/Outbox, event contract, dependency/dataset or cross-owner query. Closed/retired parents retain authorized reads; removed participants lose access. No private progress or notification change. Existing fixed-label submission.reads metric type=study-mine and traces apply; no private log content.

Collapsed by default; explicit filter/refresh uses independent memory state and preserves open answer/grade drafts. Selecting a row explicitly opens the original freshly authorized Assignment; its existing component reloads own current answer and detailed grade. Labels are current metadata, while submitted instruction versions remain pinned in original detail. No stale overview body/score is reused for a write. Invalid metadata or failed reads clear rows/cursor and offer explicit retry without a false empty result. Denial clears Group details; hide/unmount aborts and clears private state. No persistent browser storage. Current pages may shift after source changes; refresh begins at the start.

OpenAPI: contracts/openapi/study-my-submissions.yaml. Tests cover cross-author/Study/Space scope, OWNER own work, current VIEWER, revoked membership, filtered keyset across assignments, exact grade annotations, score0/direct/rubric parity, concurrent source writes, archived reads without Outbox changes, strict HTTP/Gateway vocabulary, original queue regressions and mobile390px.

Deployed validation evidence: [study-my-submissions-validation.txt](study-my-submissions-validation.txt) and `.local/study-my-submissions-validation.json`.

Study-wide own request overview: [study-my-resubmissions.md](study-my-resubmissions.md).

Study-wide own feedback overview: [study-my-feedback.md](study-my-feedback.md).

Study-wide own assignment/submission-presence overview: [study-my-assignments.md](study-my-assignments.md).

Course-wide own submission/grade overview: [course-my-submissions.md](course-my-submissions.md).
