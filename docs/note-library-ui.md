# Canonical private Note library UI

Personal Graph now has a collapsible My Notes card. The existing Content GET Notes
API lists active canonical summaries, including ordinary and Daily Notes. Pages use
20 rows and the UUIDv7 after cursor in ascending creation-ID order. Displayed counts
mean loaded rows, not total Notes or graph node counts. Rows deduplicate immutable
IDs when paging across concurrent changes; this is not a snapshot or search filter.
Retired Notes are excluded by Content. No graph/search projection completeness is
required to open a listed Note. Selection fetches canonical editor/body/metadata/
links/attachments/Concept data and preserves the immutable Note ID without adding
fabricated graph nodes. The outside-graph label now applies to any selection source.

Opening/refreshing starts at the first page. Canonical editor saves and normal UI
Note creation refresh an open list; API/Daily creates elsewhere can be discovered
with explicit list refresh. Closing aborts reads and clears private list rows.
Space/hide/logout reset the component; stale/aborted responses are ignored. Failed
reads clear all loaded rows; 401/403/404 clear the entire parent private view. State
is memory-only with no browser storage or URL query exposure. Titles are escaped
text and only summary ID/title are retained in browser list state.

No backend/domain API/schema/event/dependency/ownership change. Build/import Gateway
on both nodes and run Install-LocalGraphLibrary.py --helm with the existing Helm
executable. Run Run-GraphWebValidation.py --public for regressions; --library-only
focuses on 202 canonical Notes, a real bounded 200-node projection, cursor paging,
canonical navigation/save, failures, stale responses and private session clearing.
Evidence: note-library-ui-validation.txt. The functional layout remains pending the
agreed later graph design work.
