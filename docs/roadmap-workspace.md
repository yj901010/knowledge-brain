# Focused Roadmap workspace — 2026-11-02

Open <https://localhost:8443/graph/roadmaps>. Browse reviewed public graphs first,
then choose **평점·순위** or **로드맵 기여·검토** with the selector, task cards,
sidebar or Ctrl/Cmd+K. **내 학습 로드맵** links to `/graph#work=learning`, where
the existing private Space/session controls remain authoritative.

## Display and retained inputs

Allowlisted fragments contain a task only: `#roadmap-work=rankings`,
`#roadmap-work=contributions`, or `#roadmap-work=all`. The unfragmented route opens
browse; unknown values normalize to browse. Domain and private/public record IDs,
answers, scores and inputs are not stored in fragments or new browser storage.

The original source workspace keeps browse selection/history separate from the
contribution editor/proposal/comparison. Switching focused tasks and opening another
reviewed graph preserves the in-scope editor and preview. A public graph's explicit
update action starts an editor pinned to that graph's inspected version. Starting
a new/update proposal or selecting a proposal retains the original explicit
replacement behavior. Complete overview remains available with the original
exclusive source-selection resets, for users who want the combined interface.

Catalog and public proposal metadata are initially loaded by the shared source
component. Unvisited rankings are not mounted; the ranking task opens the existing
ranking panel automatically. Sort/range choices persist in scope; returning to the
ranking task refreshes its derived results. The same original browse detail hosts
versioned ratings, private adoption and optional embedded personal learning, once.
Hidden source contexts are inert; tasks do not duplicate rating/adoption instances.

## Current authority and pending commands

Identity/kind and public-Space catalog permissions are read on entry, task/history
navigation, explicit access refresh and return from page hiding. Selected domain,
principal/kind and propose/review flags key all retained source state. Domain,
identity/permission changes, page hiding and failed authority checks drop retained
inputs. Checking hides/inerts the source. Successful retry normalizes the URL to
the recovered task; generation/abort boundaries ignore late authority/source reads.
Initial anonymous access may establish the existing guest identity. Later401 drops
work and asks the user to restore access in Personal Brain.

Explicit proposal/review/merge, adoption and rating write commands coordinate with
the root controls using per-command in-memory tokens. Domain/task/access controls
and source fields are disabled while a write is pending; sidebar/history requests
retain the task and explain the pending command. Read operations retain their
existing bounded timeouts and cancellation on a domain change. A released token
from an old source cannot unlock an unrelated new command. No automatic save,
retry, adoption, approval, merge, rating or progress update occurs on navigation.

## Existing contracts remain authoritative

Public proposals retain graph preview/explicit consent, exact source versions,
`pcN`/`rmpN` contracts, independent designated review, no self-approval and immutable
histories. Popularity/rating is independent of review and does not establish
medical correctness or professional credentials. Original source writers perform
fresh server authorization and stale-version checks.

Adoption remains an explicit current-version copy into an authorized PRIVATE
PERSONAL Space, once per Space/source/version, with immutable provenance and new
personal IDs. Public updates do not modify an adopted graph or transfer private
progress. An initial personal-Space catalog404 keeps the empty adoption panel
open with preparation/reload guidance; no old private value remains. A fresh valid
catalog and explicit destination/consent are still required.401/403 and denied
adoption writes continue to clear source access. Ratings remain registered-only, version-pinned, independent of reviewer
membership, with original self-rating restrictions and CAS/history semantics.
Private learning records and attached evidence are not published or added to
public ranking totals. No domain API/schema/event, service, dataset or dependency
is added; existing Learning ownership and Outbox/Connect publication are unchanged.

## Local delivery

`Install-LocalRoadmapWorkspace.py --helm <absolute-path>` builds pinned static
assets/CSP/notices, captures immutable source/image/import evidence, imports both
existing nodes and rolls Gateway with existing telemetry. `--refresh` rebuilds
changed source. Exact owned ephemeral builders are cleaned after evidence capture.

`Run-GraphWebValidation.py --roadmap-workspace` runs four new workflow cases and
existing public catalog, private Roadmap, adoption, rating, graph and common design
regressions. `--focused-only` runs just the four new cases. A separate
`--public --public-only` run verifies real registered public publication/review and
versioned adoption/rating policies with a retired temporary Development reviewer.

`Verify-LocalRoadmapWorkspace.py --browser-job <job>` accepts each successful
same-image run; optional `--retired-principal` includes prior failed-run authority
in the final read-only Space audit. It compares immutable sources/images, original
app/monitoring/PVC identities, other images, both nodes, JVM/CDC health and alerts.
Completion evidence is in `roadmap-workspace-validation.txt` and
`.local/roadmap-workspace-validation.json`. Earlier backend/CDC-byte/rebuild/restore
proof remains historical; no canonical/PVC/offset reset or whole-cluster restore is
part of this static-client milestone.
