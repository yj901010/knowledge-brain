# Graph Note editing and revision history

The existing private Content Note API is now available from the Graph inspector:
select a Note, choose **노트 편집**, edit its title/Markdown, then **수정 저장** or
**편집 취소**. **수정 이력** reads canonical immutable revisions, five at a time,
with **이전 수정 더 보기** for older pages. History viewing does not restore or
rewrite a Note. Text initially shows as escaped Markdown source. Explicit rich preview is now documented in [note-markdown-preview.md](note-markdown-preview.md).

## Existing architecture retained

Content remains canonical owner of Note text, revisions, UUIDv7, authorization,
local transactions and Protobuf Outbox. Gateway serves the static Next export and
existing common-origin HTTPS routes. No backend API/schema/event changes, new
dependencies, cross-service SQL, direct public publishing or architecture decisions
are introduced. API/event contracts remain content-notes.yaml and content.note.v1.
The existing Content change counters and HTTP/event traces cover editor writes.
Graph titles and Wiki Links eventually converge from the same existing CDC pipeline.

## Concurrency and privacy

An editor starts from a fresh Note GET and retains its exact strong ETag. PUT sends
that If-Match, never a guessed or silently refreshed version. On 412 the draft stays
visible and saving is blocked. **최신 내용 확인** displays the current canonical
revision without changing the draft. **최신 버전으로 다시 시작** explicitly replaces
the draft and takes the new ETag; the user edits and saves again. There is no forced
overwrite, automatic conflict merge or automatic retry.

Transient save failures preserve the draft. A successful but unacknowledged save
cannot be repeated blindly: a retry still uses the old ETag and the server detects
the version change. Cancel does not write or create a revision. A successful save
keeps the original Note ID and updates the current graph label immediately while
canonical graph projection catches up.

Drafts exist only in component memory. Changing Note/Space, hiding the page through
the existing private-state policy, or logging out discards them; the form says so.
There is no autosave, localStorage or sessionStorage persistence. Unmount invalidates
pending response updates. 401/403/404 clears editor text/history/drafts and clears
the parent private view before fresh Graph authorization. Revision reads use the
same fresh Space/Identity checks as normal Note reads, including pagination.

## Local delivery and validation

Build/import `gateway` using Build-LocalSpaceImage.py and Import-LocalSpaceImage.py.
After both node import Jobs succeed, run
`Install-LocalGraphEditor.py --helm <existing-helm-executable>`. The installer checks
completed imports on every local node and rolls out only Gateway. Content and
Knowledge images and existing CDC/Registry configuration are retained.

Run `Run-BackendValidation.py --gateway` and `Run-GraphWebValidation.py --public`.
The browser runner now always includes editor.spec.ts. It tests real private
canonical writes, unchanged Note ID, strong-version conflict with an independent
writer, draft preservation/explicit restart, seven-revision pagination, simulated
503 retry, cancel, Note-switch draft clearing, desktop/mobile layout, escaped script text and immediate clearing
after real guest revocation. Existing graph/public reference tests run alongside it.
TLS uses the public CA with no bypass. Evidence is note-editor-validation.txt and
ignored `.local/graph-ui-results/editor*.png` screenshots.

Rich Markdown preview is now implemented separately in note-markdown-preview.md.
Attachments and metadata editing UI are implemented in their delivery documents.
Historical text restoration is now available: [note-restore.md](note-restore.md).
The agreed dark graph redesign remains subsequent work.

Explicit version-protected Note retirement UI: [note-retirement-ui.md](note-retirement-ui.md).
