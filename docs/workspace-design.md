# Knowledge workspace and graph design — 2026-11-02

The user approved an Obsidian-style connected knowledge interface and requested that the next work proceed in large packages with recommended defaults. This package applies a shared workspace and graph interaction layer to the existing application. The existing thirteen service boundaries, owner databases, APIs, event contracts, authentication, review authority and private defaults remain authoritative.

## Workspace

All four routes — Personal Brain, public Knowledge, public Roadmaps and Groups — share a dark workspace, persistent navigation and consistent typography, forms, borders and focus indicators. The default theme follows the supplied dark graph references. A light theme is also available. The sidebar has a compact desktop mode and a mobile drawer. Ctrl/Cmd+K opens a keyboard-accessible route/section palette; Escape restores focus. A skip link reaches the main content.

The personal screen starts with the note graph and existing note inspector/editor. Below it, existing features are grouped into notes/search, integrated knowledge, concepts/sources, learning, journal/activity, questions/decisions/bookmarks, planning, notifications and registered GitHub activity. Sidebar shortcuts navigate to these sections. They do not recreate or automatically publish data. Existing feature components and their current authorization/error/stale-draft handling remain mounted; this package does not claim to eliminate all initial API reads or introduce a new query model.

Only `brain.ui.theme` and the desktop `brain.ui.sidebar` preference may be stored in localStorage when the user changes them. No note text, IDs, titles, search terms, account selection or graph geometry are stored there. Existing private content continues to be cleared by the original session/Space/error boundaries. Preferences do not grant authorization.

## Graph interaction

Note, personal integrated knowledge, public knowledge and public roadmap graphs use the same deterministic connection-based force layout. The current server limits remain unchanged: up to200 personal items/1,000 relations, bounded public discovery and up to100 public roadmap nodes. The SVG client does not fetch undisclosed graph data, perform AI inference or change canonical relationships.

Nodes use type colors and connection-dependent size. Selection highlights immediate incident relations and neighbors while dimming unrelated items. Color and size do not imply verification or ranking. Public detail and badges retain the current canonical/version checks. Literal Claim/Qualifier values remain literals and never gain Concept edges.

Controls include drag to pan, drag to reposition a node locally, zoom and fit. Camera fitting respects the actual responsive SVG aspect ratio. Local movement and selection do not write domain data. Keyboard users can Tab to nodes, use Enter/Space to select, use arrow keys to pan the canvas, +/− to zoom, Home to fit and Escape to clear where supported. Full titles remain in accessible labels/tooltips and original lists/details remain available. Automatic labels reduce dense clutter; the user can explicitly show all names. The layout is bounded rather than continuously animated and honors reduced-motion preferences.

## Delivery and validation

`Install-LocalWorkspaceDesign.py --helm <absolute-helm-path>` builds the pinned static Next.js frontend, generates the same-origin CSP hashes and license notices, builds/imports the Gateway image to both existing nodes and rolls only Gateway. `--refresh` explicitly rebuilds a changed source. It preserves Gateway Deployment identity and telemetry, captures frontend/runtime source archives plus build/import/image evidence, then cleans only its owned ephemeral builder.

`Run-GraphWebValidation.py --workspace-design` runs the new navigation/graph/dense/mobile checks together with the existing note graph, editor, search, integrated network and repeated public refresh/domain-switch regressions. The existing `--public-workbench` regression separately verifies registered public contribution and independent authority. All browser tests use project CA trust over HTTPS. No TLS bypass, new package, external font/image dependency or schema migration is introduced.

Completion evidence is recorded in `workspace-design-validation.txt` and `.local/workspace-design-*.json`. Prior public-discovery, backup and observability proof remains historical. The full domain content curation, production delivery, wider HA/load/SLO work and further feature-specific workflow refinement remain separate. This package does not claim that every historical collaboration/learning/planning test was rerun.


2026-11-07 maintenance: current interactive graph semantics, bounded Worker/fallback, mobile/modal focus, general public tables and source/compiled asset replacement are documented in [public tables and accessibility](public-tables-accessibility.md). The original tag-only milestone installer above predates immutable digest releases; use the reviewed digest/scanning delivery procedure in [delivery](delivery.md) for the current baseline. Historical milestone evidence remains unchanged.
