# Focused public knowledge workspace — 2026-11-02

Open <https://localhost:8443/graph/knowledge>. The route starts with merged public
search and graph. The selector, task cards, sidebar and Ctrl/Cmd+K switch between
discovery, Concept contribution/review and typed record/evidence/verification work.
The existing original writers and source-version controls remain authoritative.

## Navigation and current scope

Fragments contain an allowlisted task only: `#knowledge-work=contributions`,
`#knowledge-work=verification`, or `#knowledge-work=all` for the complete overview.
Discovery uses the unfragmented route. The old `#discovery`, `#contributions` and
`#verification` links are accepted and normalized after authorization. Invalid
fragments fall back to discovery. No private content or domain/entity ID is stored
in the URL, browser storage or new task cards.

Unseen panes mount on first use. Visited panes stay hidden and inert while retaining
in-scope drafts and their existing read behavior. Browser Back/Forward uses the
same focus navigation. Current identity and public-Space catalog permissions are
read on entry, navigation, explicit refresh and return from browser hiding.

The selected domain, application principal/kind and propose/review flags key all
retained state. Domain changes clear immediately. Changed identity or reviewer
authority unmounts prior panes; proposal rights remain independent. During a check,
panes are hidden/inert. Failed checks drop drafts and provide retry guidance;
successful recovery normalizes the URL to the actual recovered task. Aborted/late
checks cannot overwrite a newer selection. Hiding the page drops retained state.
Initial anonymous access may establish the existing guest identity. A later401
clears the current work and asks the user to restore access in Personal Brain.

## Publication and review

Explicit proposal/review/merge commands report pending work to the shared controls.
Domain/task refresh and selection are disabled during these commands; sidebar and
history requests retain the current task and explain that the command is pending.
There is no automatic write, retry, save, approval, merge or publication on navigation.

Existing Concept proposal/compare/review/merge, typed record forms, public consent,
exact reference pins, strong versions, stale-write handling, histories and fresh
server authorization are preserved. The complete overview has the same sources as
the focused panes. Catalog refresh buttons remain source-specific.

Guest browsing and registered proposal rights do not grant reviewer membership.
Designated review remains independent of `CLAIM_EXPERT_VERIFY` and
`MAPPING_OFFICIAL_REVIEW`; capability checks still occur at the original server
writers. Neither the task cards nor a reviewer badge establishes professional
credentials, clinical correctness or official terminology status. Starter metadata
still requires explicit selection/consent/proposal and independent review/merge.

## Local delivery and validation

`Install-LocalKnowledgeWorkspace.py --helm <absolute-path>` builds pinned static
assets/CSP/notices, captures source and image evidence, imports both existing nodes
and rolls Gateway with the existing telemetry overlay. `--refresh` rebuilds source
changes. Only exact owned ephemeral builders are cleaned after evidence capture.

`Run-GraphWebValidation.py --knowledge-workspace` runs four focused scenarios,
discovery layout and four common navigation/graph regressions. `--focused-only`
runs the four new scenarios. Separate `--public --public-only` and
`--public-workbench` runs exercise original publication/review workflows, including
temporary Development-only reviewer/capability fixtures and their retirement.
They use real local Keycloak users and verified project CA, without TLS bypass.

`Verify-LocalKnowledgeWorkspace.py --browser-job <job>` accepts each successful
same-image run. Optional `--retired-principal <application-UUID>` includes a failed
run's already retired test authority in the final read-only Space audit. It checks
source archives, image imports, both nodes, original application/monitoring/PVC
identities, other application images, JVM metrics, CDC tasks and alerts.

Completion evidence is in `knowledge-workspace-validation.txt` and
`.local/knowledge-workspace-validation.json`. Earlier backend/public-discovery
rebuild and backup/restore results remain historical. No new dependency, service,
domain API/schema/event, cross-owner DB access, direct event publisher, dataset,
automatic sharing or AI is introduced by this static-client milestone.
