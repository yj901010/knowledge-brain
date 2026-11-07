# Historical local application feature installers

The current Java application baseline uses immutable `image.digest` values with
an empty `image.tag`. The service chart explicitly rejects setting both
([chart image helper](../infra/charts/brain-service/templates/_helpers.tpl#L3)).
The feature installers listed below were written for older tag-based milestones.
They can otherwise build/import images, provision transport or secrets, or write
local values before Helm discovers the incompatible image selection.

Each entry point now calls
[`require_tag_baseline`](../scripts/local_feature_installer_guard.py) immediately
after argument parsing/selector validation, before commands, Cluster construction, builds, provisioning,
values/staging writes or deployment evidence writes. The check reads only the local
application values selected by that entry point. A nonempty digest stops the command
with the affected paths and current workflow guidance. Missing/unreadable files,
invalid YAML, duplicate mapping keys, a missing/nonmapping image, nonstring image
fields, malformed digests or simultaneous tag/digest selection also stop execution.
Errors do not print values-file contents. Existing PyYAML is reused; no dependency,
shell command, network call or Kubernetes query was added to the check.

A historical checkout with tag-based affected values remains compatible, including
an absent/empty digest and Helm image fields inherited from base values. Passing this
check does not validate historical prerequisites, image imports, scans or deployment
safety. Original installers still enforce their original checks and perform their
original operations. `--help` is handled by the original parser before this check.
Do not remove a current digest to make an old feature installer run.

## Current supported application-image workflow

Follow [Java runtime maintenance, Upgrade sequence](java-runtime.md#upgrade-sequence):
run `scripts/Run-DeliveryCI.py --images` for independent owner-database tests,
exact-source frontend assets and images; retain receipts and scan each exact archive;
import those same archives on both local nodes; compare manifest/configuration
digests; prepare reviewed image-only Helm changes using `image.digest` and an empty
`image.tag`; preserve other settings/credentials/Deployment/PVC identities and perform
the documented serial rollout and relevant continuity/CDC/browser checks.

For a complete release lock and GitOps staging, follow the existing
[delivery runbook](delivery.md#prepare-a-digest-pinned-gitops-release). This guard
does not implement a replacement release pipeline, scan waiver, automatic deployment
or new permission/approval flow. Remote repository delivery and adoption retain the
limits already documented there.

## Explicit affected inventory

All names below are under `scripts/`. Each source link points to the tag write,
Helm tag override or delegated installer call which establishes why it is covered.
Application names select `infra/environments/local/<name>-values.yaml` only.

| Historical entry point / source evidence | Application values checked | Mechanism |
| --- | --- | --- |
| [Install-LocalBrainNetwork.py](../scripts/Install-LocalBrainNetwork.py#L26) | space, knowledge, content, brain-query; `--enable`: brain-query; `--gateway`: gateway | YAML tag write |
| [Install-LocalClaimActivity.py](../scripts/Install-LocalClaimActivity.py#L23) | knowledge, activity, gateway | YAML tag write |
| [Install-LocalClaimCitations.py](../scripts/Install-LocalClaimCitations.py#L23) | knowledge, gateway | YAML tag write |
| [Install-LocalClaimQualifiers.py](../scripts/Install-LocalClaimQualifiers.py#L23) | knowledge, gateway | YAML tag write |
| [Install-LocalClaimUsages.py](../scripts/Install-LocalClaimUsages.py#L23) | knowledge, gateway; `--gateway-only`: gateway | YAML tag write |
| [Install-LocalConceptNotes.py](../scripts/Install-LocalConceptNotes.py#L23) | content, gateway | YAML tag write |
| [Install-LocalExternalMappings.py](../scripts/Install-LocalExternalMappings.py#L23) | knowledge, gateway | YAML tag write |
| [Install-LocalGraphAttachments.py](../scripts/Install-LocalGraphAttachments.py#L23) | gateway | YAML tag write |
| [Install-LocalGraphConceptLinks.py](../scripts/Install-LocalGraphConceptLinks.py#L23) | gateway | YAML tag write |
| [Install-LocalGraphEditor.py](../scripts/Install-LocalGraphEditor.py#L23) | gateway | YAML tag write |
| [Install-LocalGraphJournal.py](../scripts/Install-LocalGraphJournal.py#L23) | gateway | YAML tag write |
| [Install-LocalGraphLibrary.py](../scripts/Install-LocalGraphLibrary.py#L23) | gateway | YAML tag write |
| [Install-LocalGraphLinks.py](../scripts/Install-LocalGraphLinks.py#L23) | gateway | YAML tag write |
| [Install-LocalGraphMarkdown.py](../scripts/Install-LocalGraphMarkdown.py#L23) | gateway | YAML tag write |
| [Install-LocalGraphMetadata.py](../scripts/Install-LocalGraphMetadata.py#L23) | content, gateway | YAML tag write |
| [Install-LocalGraphRetirement.py](../scripts/Install-LocalGraphRetirement.py#L23) | gateway | YAML tag write |
| [Install-LocalGraphSearch.py](../scripts/Install-LocalGraphSearch.py#L23) | gateway | YAML tag write |
| [Install-LocalGroupWorkspace.py](../scripts/Install-LocalGroupWorkspace.py#L64) | gateway | YAML tag write |
| [Install-LocalKnowledgeWorkspace.py](../scripts/Install-LocalKnowledgeWorkspace.py#L64) | gateway | YAML tag write |
| [Install-LocalNoteConcepts.py](../scripts/Install-LocalNoteConcepts.py#L30) | space, knowledge, content, gateway | YAML tag write |
| [Install-LocalNoteGraphProjector.py](../scripts/Install-LocalNoteGraphProjector.py#L25) | brain-query | YAML tag write |
| [Install-LocalNoteProjector.py](../scripts/Install-LocalNoteProjector.py#L22) | brain-query | Helm tag override |
| [Install-LocalPersonalConceptAliases.py](../scripts/Install-LocalPersonalConceptAliases.py#L23) | knowledge, gateway | YAML tag write |
| [Install-LocalPersonalConceptClaims.py](../scripts/Install-LocalPersonalConceptClaims.py#L23) | knowledge, gateway | YAML tag write |
| [Install-LocalPersonalConceptEditor.py](../scripts/Install-LocalPersonalConceptEditor.py#L23) | gateway | YAML tag write |
| [Install-LocalPersonalConceptLibrary.py](../scripts/Install-LocalPersonalConceptLibrary.py#L23) | gateway | YAML tag write |
| [Install-LocalPersonalConceptRetirement.py](../scripts/Install-LocalPersonalConceptRetirement.py#L23) | gateway | YAML tag write |
| [Install-LocalPersonalSources.py](../scripts/Install-LocalPersonalSources.py#L23) | knowledge, gateway | YAML tag write |
| [Install-LocalPublicConceptReferences.py](../scripts/Install-LocalPublicConceptReferences.py#L23) | knowledge, gateway | YAML tag write |
| [Install-LocalPublicContributions.py](../scripts/Install-LocalPublicContributions.py#L34) | space, knowledge, gateway | YAML tag write |
| [Install-LocalPublicDiscovery.py](../scripts/Install-LocalPublicDiscovery.py#L60) | space, knowledge, brain-query, gateway | YAML tag write |
| [Install-LocalPublicKnowledgeWorkbench.py](../scripts/Install-LocalPublicKnowledgeWorkbench.py#L60) | space, knowledge, gateway | YAML tag write |
| [Install-LocalPublicRoadmaps.py](../scripts/Install-LocalPublicRoadmaps.py#L36) | space, learning, gateway | Delegates after transport/contract provisioning |
| [Install-LocalRegisteredLogin.py](../scripts/Install-LocalRegisteredLogin.py#L34) | space, identity, gateway | YAML tag write |
| [Install-LocalRoadmapAdoptions.py](../scripts/Install-LocalRoadmapAdoptions.py#L36) | learning, gateway | Delegates after transport/contract provisioning |
| [Install-LocalRoadmapRatings.py](../scripts/Install-LocalRoadmapRatings.py#L36) | space, learning, gateway | Delegates after transport/contract provisioning |
| [Install-LocalRoadmaps.py](../scripts/Install-LocalRoadmaps.py#L19) | space, learning; selectors described below | YAML tag write |
| [Install-LocalRoadmapWorkspace.py](../scripts/Install-LocalRoadmapWorkspace.py#L64) | gateway | YAML tag write |
| [Install-LocalSourceCitations.py](../scripts/Install-LocalSourceCitations.py#L23) | knowledge, gateway | YAML tag write |
| [Install-LocalTypedClaims.py](../scripts/Install-LocalTypedClaims.py#L23) | brain-query, knowledge, gateway | YAML tag write |
| [Install-LocalWorkspaceDesign.py](../scripts/Install-LocalWorkspaceDesign.py#L64) | gateway | YAML tag write |
| [Install-LocalWorkspaceFlow.py](../scripts/Install-LocalWorkspaceFlow.py#L64) | gateway | YAML tag write |

`Install-LocalRoadmaps.py` retains its existing single-service selector validation:
`--brain-query-only`, `--notification-only`, `--planning-only`, `--gateway`,
`--activity-only`, `--space-only`, `--media-only`, `--knowledge-only`,
`--content-only`, then `--learning-only`. Without one it selects Space and Learning.
Invalid combinations still fail at argument validation before the guard. The
selected branch alone is inspected. Brain Network keeps `--gateway` before
`--enable`; its default is Space/Knowledge/Content/Brain Query. Claim Usages retains
its Gateway-only option. Workspace refresh flags select evidence/rebuild behavior;
they do not select a different application set for compatibility checking.

The maintained `Install-LocalCassandra.py`, `Install-LocalDelivery.py`,
`Install-LocalHeadlamp.py`, `Install-LocalObservability.py` and
`Install-LocalOpenSearch.py` are excluded and unchanged. Infrastructure values,
unselected application files, charts and runtime resources are not modified or
broadly rejected by this guard.

## Verification and limits

Run the isolated regression suite:

```powershell
python -B -m unittest discover -s tests/delivery -p test_local_feature_installer_guard.py -v
```

The 12 tests execute all 42 real guarded entry points and 14 additional selector
variants against digest-pinned temporary fixtures. Subprocesses, shell calls,
Cluster construction and file mutations are trapped before they could run. They
also cover source-inventory drift, unchanged infrastructure entry points, selected
file reads, tag-only compatibility and continuation to the original read-only
context command, invalid/duplicate/unsafe YAML and image fields, absent files,
unchanged bytes and actionable workflow guidance. A later incompatible service
also blocks earlier service/transport work. These checks passed locally. A separate
read-only pass over the current local values rejected all 42 historical defaults;
it made no cluster calls and changed no values.
The host sandbox's default temporary-directory ACL required the test runner to use
an existing writable fixture parent inside the repository; fixtures were removed
by the test runner. The suite is included in the existing CI
`python -m unittest discover -s tests/delivery -v` command; no workflow change is
needed. No real installer, build, Helm or Kubernetes operation ran.

This is a preflight over on-disk selected local values, not a lock against concurrent
file edits, a live cluster image/authority check, full merged Helm validation, image
provenance/scan proof, rollback proof or certification that a historical milestone
can be replayed safely on a current cluster. The inventory test detects unlisted
`Install-Local*.py` direct tag writers, `image.tag=` overrides and delegation to the
historical Roadmaps installer; substantially different future entry points need an
explicit inventory review. Existing milestone guides remain historical evidence.
