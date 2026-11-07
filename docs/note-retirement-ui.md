# Private Note retirement UI

The inspector now exposes the existing Content DELETE API as **노트 폐기**.
AGENTS.md and PROJECT.md were read before implementation. No service boundary,
canonical lifecycle, API, event, database migration or dependency changes.

## Usage and lifecycle

Open an active Note, choose 노트 폐기, read the consequences, tick
복원할 수 없음을 확인했습니다, then choose 폐기 확정. 폐기 취소 does not write.
Retirement creates the existing RETIRED revision/event. It is not physical erasure:
authorized canonical history retains text. There is currently no restore UI/API.
Attachments are unlinked by Content; their Media objects are not deleted.
Daily date/timezone bindings remain, so retired Daily Notes cannot be recreated
at the same date. These consequences appear before confirmation.

## Concurrency and errors

DELETE uses the exact strong ETag from the canonical Note read. 412 disables
confirmation. The user explicitly inspects the latest title/body/version and
chooses 최신 버전으로 폐기 다시 확인, then must acknowledge again. No automatic
version retry. 503/timeouts retain the panel, clear acknowledgement and never
claim success. A retry uses the same ETag; an already-retired Note returns 404
and the existing authorization/privacy clearing path runs.
401/403/404 clear private inspector state and refresh access. Epoch checks ignore
late responses after Note/Space changes or unmount. Confirmation/acknowledgement
is memory-only and resets on switching, hiding or logout; no browser storage.

## Lists and projections

On 204, the inspector and relation panels close, the canonical Note collection
and Journal refresh, search results reset, and the retired ID and edges are
removed immediately from the current graph. A memory-only set filters stale
in-flight graph projection responses in this Space session. Search and graph
backends remain event-driven projections; convergence may take a short time.
The full authorization-filtered canonical Content API stays authoritative.

## Validation

Real-browser tests create their own isolated guest fixtures, exercise actual
stale ETags and canonical history, foreign denial, failure retry, Daily binding,
Note-switch acknowledgement, responsive width and revocation. Existing editor,
Markdown, attachment, library, Journal and public workflows are regression-tested.
See note-retirement-ui-validation.txt for the actual deployed evidence.

Retired Notes can now be rediscovered and read from the
[retired Note archive](retired-notes.md). Retirement itself remains final;
[historical text restoration](note-restore.md) applies to active Notes only.
