# Concept-to-private-Note navigation

AGENTS.md and PROJECT.md reviewed before implementation. Content remains the
canonical owner of both Notes and Content-Concept references. Knowledge owns
Concept definitions and lifecycle. This feature adds a same-owner canonical read
and an index; it does not join service databases or change V1 event contracts.
Mixed Note/Concept graph projections and the agreed graph redesign remain future
work.

## User flow

Open the personal Concept library and select a Concept. Under its detail, **이
개념을 사용한 노트** shows active Notes in the current private Space that retain
the selected immutable Concept ID. Notes are loaded in actual 20-item UUIDv7
cursor pages; **연결된 노트 더 보기** loads later pages and **연결된 노트
새로고침** resets the first page. The visible count describes only loaded Notes.
Identical titles remain separate by Note ID. Titles are rendered as escaped text.
Empty and failed reads have separate messages.

A Note row opens the existing canonical Note inspector, including Notes outside
the current graph window. The personal Concept detail stays mounted, so its
unsaved edit is retained during Note navigation. A successful Note title/body
save, Note retirement or Note-Concept reference save signals only the reverse
Note list to reload. Reference-only saves do not remount the Note editor or erase
its unsaved body. Reverse refresh does not remount the Concept editor or clear
its draft, history or conflict state. Explicit selection of a different Note
still replaces the old Note inspector as in existing navigation.

The list reflects saved references, not unsaved checkbox selections. It does not
create Notes or references. Changes made elsewhere appear on explicit refresh;
each page is an independent canonical read rather than a frozen collection
snapshot. Concept retirement retains references, so the currently retained
retired detail can still list its active Notes. Retired Notes and removed
references are excluded. The library still does not browse or restore retired
Concepts, and this UI currently starts from personal Concepts.

## API and ownership

`GET /api/spaces/{spaceId}/concept-references/{conceptId}/notes` belongs to Content.
Gateway exposes only this GET route and permits only bounded `limit` and `after`
parameters. Content independently checks their shape, duplicates, UUIDv7 and
limit (default 20, maximum 100). Every request performs fresh Space NOTE_READ and
Identity session authorization before querying Content's own tables.

The response is `{items:[{noteId,title}],nextAfter:UUIDv7|null}` with `no-store`.
Rows join `note_concept_link` and `note` on both Space ID and Note ID, restrict to
the authorized Space and ACTIVE PRIVATE Notes, and use exclusive ascending Note
UUIDv7 pagination with `limit+1`. V7 adds the Content-owned
`(space_id,concept_id,note_id)` lookup index without changing existing data.
No Note body, Concept definition, global count or target lifecycle is returned.

This endpoint does not call Knowledge or check whether the target Concept
exists. Unknown, foreign and retired target IDs share the same reference lookup
semantics: return this authorized Space's retained references, or an empty page.
It cannot reveal another Space's Notes or a private target definition. A foreign
Space is denied. Missing/revoked credentials and authority/storage failures
return generic errors without private data. Reads produce no new revision or
Outbox record. Existing mutation/event behavior remains unchanged.

The metric `brain.content.concept.reads{type="linked_notes"}` records successful
reads without per-ID tags. Existing Gateway tracing and generic API errors apply.
The OpenAPI contract is `contracts/openapi/concept-notes.yaml`.

## Browser lifecycle

Reads use same-origin credentials, no-store, rejected redirects and an eight
second request bound. Changing Concept, Space or reverse-refresh generation hides
the old list immediately and aborts earlier operations. Abort/epoch checks reject
late responses. A failed page read clears the loaded list; manual retry starts a
fresh first page. Closing the detail/library, hidden-page clearing, logout or
authority denial clears its mounted private lifetime. No browser storage is used.

## Validation and delivery

Content API tests cover fresh authorization, no-store, safe cursors, spoofed
headers, missing/duplicate sessions and generic persistence failures. Real
PostgreSQL tests cover 20+1 paging, same-title IDs, Space isolation, V7 migration,
rename/reference removal/Note retirement, retained target IDs and read-only
revision/Outbox behavior. Gateway tests cover exact GET routing and query bounds.

The confined browser test uses real isolated guest fixtures and 22 linked Notes,
20+2 pages, retained retired Concept references, canonical navigation outside a
graph window, independent edit drafts, saved reference changes, Note rename and
retirement, delayed responses, failure/retry, revocation and mobile width. Only
the graph-window response is bounded to empty in that scenario; canonical Note,
reference and reverse-read APIs are real. The previous browser suite is retained.

Build and import both Content and Gateway with the existing local helpers, then
install with `scripts/Install-LocalConceptNotes.py --helm PATH`. Focused browser
validation: `python scripts/Run-GraphWebValidation.py --concept-notes-only`.
The full `--public` suite includes this scenario. Backend validation source is
chunked into owned ConfigMaps, like the image builder, to respect Kubernetes'
per-ConfigMap size limit. Actual execution evidence is recorded separately in
`concept-notes-validation.txt`.
