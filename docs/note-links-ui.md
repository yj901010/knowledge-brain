# Canonical Wiki Link and Backlink UI

Selecting a Note now reads its outgoing Wiki Links and grouped incoming Backlinks
from the existing Content APIs. Both page 20 items with independent cursors.
Resolved links and backlink sources open canonical Notes, including Notes outside
the graph window. Alias labels and fragments are text; fragments do not assert an
existing heading or perform heading navigation. Missing, ambiguous and retired
targets have distinct labels and cannot be opened from outgoing rows.

Bindings retain immutable Note IDs across rename; this UI never reparses Markdown
or uses graph projection counts as canonical backlinks. Repeated outgoing links
remain separate; incoming rows group by source and show occurrence counts. Reads
can change between pages. If outgoing linkVersion changes while paging, all data
is cleared and the user must refresh, avoiding mixed outgoing snapshots.

Fresh HTTPS Content/Space/Identity authorization remains unchanged. All private
state stays in memory, clears on Note/Space/hide/logout, and ignores aborted or
stale responses. Failed reads clear both lists. Permission denial clears the parent
private view. Saving the editor remounts link metadata; explicit refresh also
reloads both lists. No backend, schema, event, dependency or ownership changes.

Build and import Gateway on both nodes, then Install-LocalGraphLinks.py --helm
with the existing Helm executable. Run Run-GraphWebValidation.py --public to check
real links/backlinks, outgoing and incoming pagination, rename navigation, missing/
ambiguous/retired labels, failure recovery and revoked-session clearing alongside
existing graph/editor/search/public regressions. Evidence: note-links-ui-validation.txt.
