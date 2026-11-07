# Public tables and workspace accessibility

Public Knowledge offers graph/table views of its currently loaded canonical graph
and list/table views of one current search page. The native table includes title,
kind, typed summary, verification and version. Column sorting affects those loaded
rows only; it does not pretend to sort the entire corpus. Selecting a row opens the
existing canonical detail. Literal Claim objects, including UUID-shaped strings,
remain literals. References are interpreted only when their declared type permits it.

Search pagination retains the last applied query/kind even if the form is edited.
The applied filter and page position remain visible. A later page moves keyboard
focus to its results region. Domain/session boundaries invalidate late reads and
clear obsolete tables; failed reads do not retain authoritative-looking old data.
The narrow-screen table has a named, keyboard-focusable horizontal scroll region.
No new public write, authority grant, endpoint or persistent client cache is added.

The shared shell uses landmarks and names containing the visible control labels.
A closed mobile drawer is hidden and inert. An open drawer confines focus and
makes the body inert; Escape returns focus to the menu control. The existing
command palette remains keyboard accessible. The new-Note composer uses a native
modal dialog, initial title focus, Escape/return focus, disabled pending fields
and an in-dialog error. Losing current identity clears the unsubmitted Note draft.
Dark/light foreground, primary-action and shortcut contrast have measured fixes.

Interactive SVG graphs expose a named group with individually named, keyboard
operable node buttons. A focusable image role is inappropriate for this interactive
container. Visually ellipsized names retain full text and accessible names.
Camera controls, selection, dragging and canonical detail behavior are preserved.

Graphs retain the original deterministic bounded layout. Up to80 nodes calculate
inline; larger graphs calculate in a same-origin module Worker. Only immutable
IDs and indexed endpoints cross the Worker boundary, with at most200 nodes and
1,000 links. Message payloads contain no titles, document bodies or credentials. Outputs must have
the exact IDs and finite bounded coordinates. Completion, error, timeout, geometry
change and unmount terminate the Worker; obsolete work cannot publish points into
another graph. If Workers are unavailable, the identical calculation yields
between small batches, with cancellation. Oversized input displays a failure
rather than silently omitting nodes. CSP permits only same-origin Workers.

The frontend stays a static Next.js export hosted by Gateway, with no Next server
or image optimizer. Next16.4.5 is a same-minor maintenance update. Static export
installation replaces the managed generated tree and retains a reversible backup
under `.local/graph-export-backups`; previous hashed chunks cannot accumulate in
the next image. The same installer also moves aside the managed compiled export
under Gateway's `target/classes`: Maven resource copying otherwise retains obsolete
hashed chunks between local builds. The next Maven resource phase recreates that
output from the current export. Neither private data nor PVCs are part of this operation.

Validation entry points:

```powershell
python scripts/Run-GraphWebValidation.py --accessibility
python scripts/Run-GraphWebValidation.py --graph-performance
```

These use the existing trusted local HTTPS bridge. Axe4.14 is development-only;
unmodified MPL-2.0 notices and its bundled third-party notices are retained under
`infra/dependencies/accessibility`. It is not imported into browser/runtime code.
The suites cover four routes, two themes, desktop/390px, modal/drawer focus,
registered private Group forms, unsubmitted public drafts, real loaded private
nodes, public tables and late/failed reads. Public table tests read the existing
Development validation fixtures and never submit public knowledge. Registered
checks create only their own private fixtures, with no reviewer grant.

Client performance fixtures deliberately intercept the graph response to isolate
layout/render/camera latency at normal and4x-throttled CPU. They do not measure
backend/storage latency, sustained load or production capacity. Automated axe
results, including incomplete checks needing human judgement, are scoped evidence,
not complete WCAG conformance or screen-reader certification. See the companion
validation record for the exact deployed image and observed results.
