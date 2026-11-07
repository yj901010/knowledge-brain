# Note-to-Concept catalog, concurrency and history UI

AGENTS.md and PROJECT.md checked first. Existing Knowledge personal Concept catalog
and Content reference APIs remain canonical. No cross-service SQL, copied private
Concept definitions, new dependency, schema/event change or architecture change.

The Note inspector loads personal Concepts through 20-item UUID cursor pages,
with ID deduplication, a loaded-title filter and explicit refresh/more controls.
It can navigate beyond the previous 100-item cutoff. Creation remains explicit,
creates only a personal Concept, refreshes the first page and does not auto-link.
Public references continue through the existing public picker and review-governed
Knowledge APIs. All reference IDs count toward the same 20-reference limit.
At the limit, new checkboxes are disabled; existing references remain removable.
Outside-catalog or retired IDs remain removable with an opaque ID label without
retrieving another private Space's definition. Catalog failure clears rows but
preserves the independently loaded Content reference snapshot.

Each immediate checkbox write carries the exact c-ETag read from Content.
412 blocks mutation, retains the displayed snapshot, permits explicit latest-ID
inspection, then explicit restart. It never automatically overwrites a concurrent
change. Transport errors retain the old display with a result-unknown message;
retry uses the same ETag. Explicit refresh can resolve uncertain outcomes.
The Note's text/version and Concept's canonical title/version are unchanged by
linking. Reference revisions are displayed as immutable ID snapshots in descending
5-item version cursor pages, with read-only timestamps and no restore operation.
Historical labels are not reconstructed from current titles or private foreign data.

API calls use no-store, same-origin credentials, abort/timeouts, per-component
lifetime and request epochs. Switching Note/Space, hiding or logout unmounts the
component and clears catalogue, input, conflicts and history; late responses cannot
populate another Note. 401/403/404 clear private state through the parent authority
refresh. No browser storage, autosave, publication, or AI parser.

Validation uses real isolated guest fixtures and a 101-Concept catalog, actual
Content concurrency/history, shared limits, catalog outage, stable link IDs,
unchanged Note body/version, foreign denial, responsive width and revocation.
Actual evidence: note-concepts-ui-validation.txt.

Personal catalog entries can also open their own description/edit/revision panel without mutating a Note reference. See [personal-concept-editor.md](personal-concept-editor.md).

Personal Concept retirement with retained revisions and Note references: [personal-concept-retirement.md](personal-concept-retirement.md).
