# Group task workspace — 2026-11-02

The next large UI package consolidates the existing Group tools into a shared role-scoped workboard. It changes static web assets served by Gateway. Domain APIs, owner databases, service boundaries, ETags, idempotency keys, Outbox/event contracts, privacy and consent policies remain authoritative. No migration, dependency or automatic data sharing is added.

## One active group

Owned Groups and participating Groups use the same workboard. Opening one entry closes the other selected entry; pending selection requests are cancelled at the existing scope boundary. Owned/participating selection controls coordinate their busy states so another group selection does not interrupt an in-progress creation/acceptance command. Normal creation keys and invitation semantics remain unchanged.

The full overview is available alongside ten task categories: own assignments, own submissions/grades, own resubmission requests, own feedback, owner feedback/request review, owner grading, Studies/shared Roadmaps, Courses/curriculum, own notifications and owner membership/invitations. Owner-only tasks are omitted for MEMBER/VIEWER. Existing VIEWER read-only and self-grading restrictions still apply in their source components and APIs.

Sidebar and Ctrl/Cmd+K navigation use allowlisted category-only fragments such as `/graph/groups#group-work=assignments`. Links do not encode a private Group/Space/Study/Submission ID or body. The user explicitly selects a current authorized Group. Before selection, navigation provides a selection instruction. An unavailable owner task falls back to overview with guidance after current permission verification. Every successful check normalizes the fragment to the task actually opened, including recovery after a failed check; a failed requested task cannot silently carry over to the next Group.

## Current authority and state

Initial entry, task changes, browser Back/Forward and explicit permission refresh re-read current participation and membership. Group/Space, active state, role, actor and membership version must agree. A role/version/actor change closes the selected context and requires fresh selection. Requests use abort/generation boundaries, so a late response from the previous Group cannot reopen it.

Unseen task panes mount on first use. Visited panes remain hidden and inert when another task is selected, preserving in-scope drafts and their existing read/timer behavior. Group/entry/authority changes unmount this state. While permission is being checked, private panes are hidden. A generic permission-check failure unmounts their drafts and shows retry guidance; a denied Group closes, and a real401 also clears authenticated page inputs and invitation state.

Existing list-to-source navigation rechecks authority and opens the original Study/Assignment/Submission. Queue metadata remains metadata: no score, answer, feedback reason or private learning progress is copied into new task cards. No inferred obligations, fabricated totals or automatic publication/completion/grade actions are introduced. The original explicit load/refresh buttons remain the source read controls.

## Presentation

Task cards, an explicit selector, current-role badge, source guidance, consistent list cards and mobile layout make participant and owner workflows easier to locate. Technical IDs and creation provenance are available in an expandable Group-information section. Existing source labels and version-specific grading, feedback/retraction and resubmission meanings are retained.

## Local operation

`Install-LocalGroupWorkspace.py --helm <absolute-helm-path>` builds the pinned static client, CSP hashes and license notices, captures immutable frontend/runtime/image/import evidence, imports to both existing nodes and rolls only Gateway. `--refresh` rebuilds changed source. Owned ephemeral builders are removed only after evidence capture; no canonical/PVC/offset reset occurs.

`Run-GraphWebValidation.py --group-workspace` runs the focused Group UI checks together with existing membership, own-work queues, owner review/grading, submission files, rubric grading, notifications, Courses and private progress regressions. It uses actual local Keycloak accounts and verified HTTPS. Adding `--focused-only` repeats just the three workboard/history/authority scenarios for visual or focused regression checks, rather than the thirteen existing regressions. `Verify-LocalGroupWorkspace.py --browser-job <job>` compares successful runs with current source/image and original runtime identities/monitoring. Completion evidence is in `group-workspace-validation.txt` and `.local/group-workspace-validation.json`.

This is a workflow/UI milestone. Prior backup, backend and public-knowledge validation stays historical. Broader production delivery, content curation, load/HA/SLO and other feature-specific polish remain separate.
