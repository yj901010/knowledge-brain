# OWNER submission grading queue

On /graph/groups, current OWNER opens Study -> Assignment -> 과제 제출 -> 채점 대기 목록 열기. The separate queue starts with 채점 검토 필요 and opens the existing freshly authorized submission detail. Scores/reasons and version-pinned manual/rubric grading stay in that original component. The current OWNER's own submissions are excluded because self-grading is forbidden. Other participants never see this queue, including when it is empty. Existing submission review queue JSON and filter vocabulary remain unchanged.

Filters and status:
- NEEDS_GRADING: all statuses except CURRENT, default.
- ALL: all other authors' submitted work, without a roster/non-submitter count.
- CURRENT: a grade on the current submitted version, with absent or RESTORED annotation on that exact latest grade version. A score of0, in direct or rubric mode, counts as a grade. This is metadata presence, not passing/verification/completion.
- OUTDATED: latest grade targets an older answer. Its old marker remains metadata; it cannot make that grade apply to a new answer.
- RETRACTED: grade on the current answer whose exact latest grade revision is withdrawn. Earlier grade-version annotations never invalidate newer grades.
- UNAVAILABLE: canonical grade/marker metadata cannot safely be classified. Unknown marker state is never treated as CURRENT and remains in NEEDS_GRADING.
- MISSING: answers with no grade; also included in NEEDS_GRADING and ALL.

Collaboration reads only assignment_submission, submission_grade and grade_retraction using existing own-database unique/FK indexes. One statement projects metadata, applies full Group/Space/Study/Assignment scope, excludes the authenticated OWNER actor, filters before LIMIT and uses ascending UUIDv7 keyset default20/max100 with limit+1. Current source/marker versions share one statement snapshot. Pages are current reads rather than a frozen export; eligibility may change between pages. Explicit refresh starts from the beginning. No score/opinion/reason/answer/file data in the ten-field row. No new IDs, mutation, audit/Outbox event, migration, event contract, dependency/dataset, cross-service SQL/FK or distributed transaction.

GET .../submissions/grading-queue has only filter/after/limit, bounded duplicate-free query, canonical cursor, empty read body, no-store. Existing registered HTTPS singleton session and current Space OWNER authority are checked before the owner database. MEMBER/VIEWER403 for every filter; revoked participation denied. Caller actor selectors are rejected. Existing closed/archived parent records retain reads while their existing grade-write rules stay authoritative. Fixed-label submission.reads type=grading-queue and existing trace/pool/health instrumentation apply; no private content in metrics/logs.

The web component is collapsed by default. It validates exact metadata fields, numeric/null/marker relationships, state classification, requested filter and stable ordered cursor before rendering. Failed/invalid reads clear rows/cursor and show an explicit refresh action. Filter/refresh do not replace an already open grade draft. Selecting a row explicitly opens the original current submission; grade storage still requires its existing confirmation and version/source checks. The queue does not automatically grade, restore, notify or update private learning progress. State is memory-only and aborts/clears on hide/unmount/denial. Other metadata/feedback queues stay independent.

API: contracts/openapi/submission-grading-queue.yaml. Tests cover missing/current/zero/direct/rubric, resubmission, historical/current withdrawal/restoration, unknown-state fail-closed, exclusion of OWNER own work, keyset filtering before limit, exact scope, concurrent source writes, archived reads without Outbox changes, fresh authorization and strict HTTP/Gateway separation from the original review endpoint.
