# Focused personal workspace and Note composing — 2026-11-02

This package follows the accepted workspace design with task-focused navigation and editing. It changes the existing static web client and Gateway assets. All service boundaries, owner databases, public/private policy, domain APIs, event contracts, Outbox publication, canonical text limits and current authorization checks retain their existing meanings. No dependency or schema migration is introduced.

## Task navigation

The Personal Brain includes an explicit task selector for graph, notes/search, integrated knowledge, concepts/sources, learning, journal/activity, questions/decisions/bookmarks, planning, notifications and registered GitHub activity. Four shortcuts reach notes, knowledge, learning and planning. The existing full overview remains available.

Application-generated focus links use only allowlisted category names, for example `/graph#work=planning` or `/graph#work=notes`. They do not contain account/Space IDs, Note IDs, titles, search text or drafts. Browser Back/Forward restores the chosen category. Unknown categories do not become executable markup or arbitrary API paths. Existing skip links retain their accessibility role.

When a focused URL is opened, unseen feature areas mount on first use. The graph/session guard continues its existing checks. Once visited, areas remain mounted while hidden and inert, retaining their current read/timer behavior and unsaved form state. This permits a goal, roadmap or Note draft to survive a change of task category. Selecting the full overview activates all areas. These rules do not claim that every background read stops when a task is hidden.

The owning page keys each area by Space and private-session epoch. Existing denial, Space change, visibility clearing, logout and Note-selection behavior still discard the corresponding local state and abort stale reads. A task switch is a UI change, not a canonical write. GitHub navigation without registered availability shows a login instruction; navigation before personal setup points to setup.

Opening a Note from another task activates the notes workspace and original inspector. Reads and commands still use their owner APIs with current authority and strong versions. No domain data is copied between tasks or Spaces.

## Note editor

The inspector can expand to use the full content width. The graph remains mounted but hidden. Returning to the graph or pressing Escape changes only layout and restores focus; the same Note editor/draft stays mounted. Selecting a different Note retains the original draft-discard rule.

The draft toolbar inserts heading, bold, italic, Wiki Link, inline code and task-list syntax into the current selection. Ctrl/Cmd+B and I insert emphasis; Ctrl/Cmd+S is an explicit save command within the existing editing form. Buttons and keyboard saves use the original strong ETag, valid-text check, conflict lock and owner request. There is no automatic save or progress/publication mutation.

Optional side-by-side preview uses the existing CommonMark/GFM renderer, URL restrictions and image-as-text behavior. On mobile it stacks vertically. Byte/code-point counters show the existing32,768-byte body bound. Draft announcements remain separate from authoritative error/status messages. Restoring old Note text still creates a new version through the original restore confirmation workflow.

## Local delivery and validation

`Install-LocalWorkspaceFlow.py --helm <absolute-helm-path>` builds the pinned static Next.js client, derives CSP hashes/license notices, captures frontend/runtime source and image/import evidence, imports to both existing nodes and rolls only Gateway. `--refresh` explicitly rebuilds changed source. Evidence is separate from the earlier design milestone; owned temporary builders are cleaned after capture. No canonical/PVC/consumer-offset reset occurs.

`Run-GraphWebValidation.py --workspace-flow` runs the focused workflow, Markdown, editor/history/restore, graph/search, learning-roadmap, goals and prior workspace interaction regression set serially in the local browser Job. It includes real Keycloak registration/multiple personal Spaces and real revoked-session handling. No operator reviewer or expert capability is granted.

`Verify-LocalWorkspaceFlow.py --browser-job <job>` compares successful browser runs with the current image/cluster, captured source archives, original Deployment/PVC identities and monitoring/CDC health. Final results are in `workspace-flow-validation.txt` and `.local/workspace-flow-validation.json`. Earlier design, public knowledge and backup proofs remain historical.
