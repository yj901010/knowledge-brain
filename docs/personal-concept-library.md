# Standalone personal Concept library

AGENTS.md and PROJECT.md reviewed before implementation. Knowledge remains the
canonical Concept owner; Content owns Note source and Concept references. This
frontend reuses existing private Knowledge APIs and the existing Concept editor.
No dependency, schema/event contract, service boundary or database change.

Under the Note library, open **개인 개념 보관함 열기**. A selected Note is not
required. The **내 개인 개념** section lists active personal Concepts in UUIDv7
creation order using actual 20-item cursor pages. **보관함 개념 더 보기** loads
later catalogue pages; **보관함 개념 새로고침** resets the first page.
Different Concept IDs with identical titles remain separate entries. Displayed
counts refer only to loaded rows; **불러온 보관함 개념 찾기** filters those titles
and is not a server-wide Concept search.

**개인 개념 작성** opens explicit title and description fields. Creation validates
1..200 title code points, description <=32 KiB UTF-8 and no NUL. **개인 개념 저장**
sends only those two fields to Knowledge, validates the canonical PRIVATE/ACTIVE
Space result and strong ETag, then opens its immutable ID in detail. It does not
create a Note or add references. Cancel discards local input without writing.
Failed or uncertain creation retains input and asks the user to check the
catalogue before duplicate creation. POST is never retried automatically.

Selecting a catalogue entry opens the existing canonical detail/editor/history/
retirement panel. Saving or retiring preserves its open detail while refreshing
the first catalogue page. Retirement retains its read-only detail and revisions
until closed; active lists exclude it. A synchronous in-memory retired-ID set,
request aborts and generations prevent old catalogue results from restoring it.
This is not a retired-Concept browser or restore operation. The public
Contribution -> Review -> Merge workflow remains separate.

A shared page revision signal refreshes only Knowledge catalogues between the
standalone library and the currently open Note Concept picker. It does not
remount Note editors, reload Content reference snapshots, change c-ETags or erase
Note drafts or reference conflict/history state. Already-open Concept detail is
an explicit version snapshot; its existing strong-ETag writes detect conflicts.
A successful create/edit/retire from either catalogue signals the other.

Closing the library clears rows, filter, create input, selected detail, conflicts,
acknowledgement and history and aborts outstanding requests. Reopening starts at
the first canonical page. Space change, hidden-page clearing, logout or authority
failure unmounts the Space/private-generation key. Late list/create responses
cannot repopulate another lifetime. Reads and writes use same-origin credentials,
no-store and eight-second request bounds. 401/403/404 clear private parent state.
No localStorage/sessionStorage or private foreign definition lookup is added.
Abort cannot roll back a request already committed by its owning service.

Validation uses real isolated guest fixtures and canonical API state: creation
before any Note exists, actual 20-item paging with duplicate-title IDs, escaped
source, list failures and late responses, title edits/retirement/history,
bidirectional catalogue refresh while exact Note and reference snapshots remain
unchanged, retained Note draft, close/switch clearing, foreign denial, mobile and
revocation. Actual Kubernetes evidence: personal-concept-library-validation.txt.

Build/import Gateway using the existing local helpers, then install with
scripts/Install-LocalPersonalConceptLibrary.py --helm PATH. Focused browser command:
python scripts/Run-GraphWebValidation.py --concept-library-only
The full --public suite includes this scenario.

Content-owned reverse reference browsing below selected
Concept detail: [concept-notes.md](concept-notes.md).

Independent personal Concept Alias management and history are available below
selected Concept detail: [personal-concept-aliases.md](personal-concept-aliases.md).

Independent personal Concept relationship management and history are available
below the Alias panel: [personal-concept-claims.md](personal-concept-claims.md).
