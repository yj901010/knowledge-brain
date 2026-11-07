# Personal Concept detail, editing and revision history

AGENTS.md and PROJECT.md checked first. The existing Knowledge Service is the
canonical owner of Concept title, description and revisions. This frontend uses
its existing private Space APIs without a new dependency, schema/event change,
cross-service SQL, or copied Content definitions. The public Contribution ->
Review -> Merge path remains separate.

Open a Note from the library or graph, then use **상세 · 수정** next to a personal Concept in the
personal Concept catalog under its linked Concepts. Detail is available whether
the Concept is linked to that Note. Opening detail is a read and does not add or
remove a reference. Title, description and current revision are read from
Knowledge using the immutable Concept ID. Description is escaped plain source;
it does not execute HTML, embed images or render a Markdown preview.

**개인 개념 편집** edits title and description explicitly. **개념 수정 저장** sends
only those two fields with the exact strong version ETag from the canonical read.
Successful saves refresh the first catalog page under the same immutable Concept ID and
invalidate outstanding old catalog reads. Content's Note source/version and its
independent Concept reference c-ETag and link IDs are unaffected. Existing Knowledge revision/outbox publication is preserved; this UI does not add
a Concept graph/search projection.

A 412 preserves the draft and blocks another write. **최신 개념 내용 확인** shows
the latest canonical description alongside the current input without replacing
it. **최신 개념 버전으로 다시 시작** explicitly replaces local input and base
version; the user can reconcile and save again. A failed or uncertain save keeps
input for retry with the same ETag. **개념 편집 취소** discards local input without
writing. There is no autosave or revision restore operation.

**개인 개념 수정 이력** reads immutable Knowledge revisions in descending
five-item pages using an exclusive version cursor. **이전 개념 수정 더 보기**
appends older rows, and selecting `v{version} · {title}` displays that revision's
original title/description and timestamp. **개념 이력 새로고침** starts at the
latest page. **현재 개념으로 돌아가기** returns to current detail. Reading or
selecting history does not modify current state.

Requests use same-origin credentials, no-store, timeouts, aborts and component
request epochs. Switching Concept/Note/Space, closing detail, hiding the inspector
or signing out unmounts private input, conflicts and history. Late responses cannot
populate another Concept. Authority-denied responses (401/403/404) clear the private
parent view. No localStorage/sessionStorage, automatic publication, AI, private
foreign definition lookup or public direct edit is introduced by the editor.
Personal Concept retirement is documented in [personal-concept-retirement.md](personal-concept-retirement.md).

The browser test creates real isolated guest Notes and Concepts, then exercises
canonical edits and a concurrent 412, explicit restart, failed-save retry, cancel,
seven real revisions in 5+2 pages, stable Note and reference versions/IDs,
same-ID title refresh and stale catalog response rejection, foreign read/write/
history denial, escaped HTML, state clearing on Concept/Note changes and close,
mobile width, and session revocation. Actual local Kubernetes and screenshot
evidence is recorded separately in `personal-concept-editor-validation.txt`
after validation completes.

Personal Concept management without selecting a Note: [personal-concept-library.md](personal-concept-library.md).
