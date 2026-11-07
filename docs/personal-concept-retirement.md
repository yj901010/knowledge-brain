# Personal Concept retirement UI

AGENTS.md and PROJECT.md checked first. Knowledge remains the canonical owner of
Concept lifecycle, revisions and ConceptRetired transactional Outbox publication.
The frontend uses its existing private DELETE API. Content remains the owner of
Note-to-Concept references. No schema, API/event contract, dependency, service
boundary or database ownership change is introduced.

Open a personal Concept with **상세 · 수정**, then choose **개인 개념 폐기**.
The **개인 개념 폐기 확인** panel explains removal from the active catalogue,
retained revisions and retained Note references. **개념 폐기에 동의합니다**
is required before **개념 폐기 확정**. **개념 폐기 취소** returns to detail
without writing. Restoring a retired Concept is not currently available.

Confirmation sends DELETE with the exact strong Concept ETag from the canonical
read. A 412 clears acknowledgement and blocks another write. **최신 개념 내용 확인**
reads the latest title and description; **최신 개념 버전으로 폐기 다시 확인**
explicitly replaces the base revision, after which acknowledgement is required
again. A failed or uncertain DELETE also clears acknowledgement; retry is an
explicit action using the same base ETag. There is no automatic retry.

A verified 204 and incremented ETag remove the immutable Concept ID from the
active catalogue immediately. Outstanding catalogue reads are invalidated and
in-memory retired IDs filter late responses during this Note panel lifetime.
The still-open detail becomes read-only and displays RETIRED. Its revision button
reads the real Knowledge history, including the retirement revision and earlier
source text. Closing detail or switching Note/Space removes this in-memory view.
The current active catalogue does not provide a separate retired-Concept browser;
the authorized history API continues to retain those revisions.

Retirement leaves the Note body/version and its independent reference c-ETag,
link IDs and reference history unchanged. The retained ID can be explicitly
removed with **목록 밖 개념 … · 연결 해제**. Public lookup never reveals a
private definition. No cross-service cascade or public Concept direct mutation
occurs, and a retired Concept cannot be added as a new reference.

Requests retain same-origin credentials, no-store, bounded timeouts, aborts and
request epochs. Closing or switching discards acknowledgement, conflict and
history. 401/403/404 clear private state through the parent authority refresh.
No browser storage, publication or AI is introduced.

The real local Kubernetes browser test covers cancellation, required
acknowledgement, concurrent edits/412, explicit latest reconfirmation, failed
DELETE retry, canonical lifecycle and immutable history, unchanged Note and
reference snapshots, removal of a retained reference, stale catalogue responses,
foreign denial, close/switch clearing, revocation and mobile width. Actual build,
deployment, test and screenshot evidence is recorded in
personal-concept-retirement-validation.txt after validation.

For focused validation run `python scripts/Run-GraphWebValidation.py --concept-retirement-only`.
The full `--public` suite includes the new retirement scenario.

Personal Concept management without selecting a Note: [personal-concept-library.md](personal-concept-library.md).
