# Private Note search in Graph UI

The Graph page now provides **내 노트 검색**: submit a title/body query, open a
result, and load further results with **검색 결과 더 보기**. Pages contain 20
items. The existing Brain Query API supplies scoped totals and Note ID/title only;
Content supplies the current canonical body and ETag when a result is selected.
Search also opens Notes outside the bounded graph view without inventing graph
nodes. Reselecting a result fetches its current canonical revision and title.

No API, schema, event, dependency or service ownership changes are introduced.
Guest and registered personal Spaces use existing fresh Identity/Space authority.
The existing OpenSearch standard analyzer is retained: this is analyzed title/body
search, not semantic search or a guarantee of arbitrary substring matching.
Projection is eventual; result order can shift during concurrent writes. The API
window is bounded to 1000 results; refine the query when more matches exist.

Queries, hits and counts remain in component memory, never URLs or browser storage.
Clear aborts pending requests and invalidates their responses. Space changes,
hiding/logout and permission denial clear private results. Failed requests clear
previous hits/counts instead of presenting them as current results. Canonical
401/403/404 clears the selected Note and private view. Titles and Markdown remain
escaped text. Explicit Note selection also discards any unsaved editor draft.

Build/import Gateway with the existing image scripts; after both node import Jobs
complete, run Install-LocalGraphSearch.py with the existing Helm executable. Only
Gateway rolls out. Run Run-BackendValidation.py --brain-query --search-storage and
Run-GraphWebValidation.py --public. The browser suite includes graph, editor,
search and public contribution/reference regression tests against real local APIs
with trusted TLS. Search tests cover title/body queries, 20+1 pagination, foreign
Space exclusion, bounded graph selection, canonical reopen/retirement, failure
clearing/recovery, pending response cancellation, revocation and mobile layout.
Run the --login browser variant to check registered claiming and Space-switch
search isolation. Evidence is note-search-ui-validation.txt; screenshots are ignored under
.local/graph-ui-results/search*.png. Final dark graph design remains later work.
