# Remote source and delivery decision review

Prepared 2026-11-07. The project owner subsequently approved the public-source,
Apache-2.0 and free-allowance Actions/initially-private GHCR choice; see the
[execution record](remote-delivery-execution.md). The original proposal below
does not itself authorize or perform
repository creation, source publication, license assignment, hosted execution,
package publication or adoption of the current applications.

## Current evidence

- The connected GitHub identity is `yj901010`. The inspected account-owned,
  accessible listing contains 24 repositories and no matching project. This does
  not establish that no organization or otherwise inaccessible repository exists.
- The local project has no Git remote or source commit. `gh` is unavailable.
  Local Git fixtures used for CI/GitOps are separate from this source repository.
- The connected tools can inspect repositories/runs and manipulate existing Git
  objects, but currently expose no new-repository creation or workflow-dispatch
  action. Those operations may need authenticated browser/Git access after approval;
  an interactive login handoff is separate from the connector's existing identity.
- The 13-service local build/scan/rollout and isolated Argo sync/drift/rollback
  foundation are delivered. They do not prove hosted Actions or GHCR delivery.
- A project license has not been selected. Dependency notices, source attribution
  and scoped authoring rights remain independent of that selection.
- The account plan, remaining private Actions allowance, billing controls and
  available repository protection features have not been verified.

## Decision choices

| Choice | Concrete proposed result | Tradeoff before execution |
| --- | --- | --- |
| Public source with Apache-2.0 for original project code | Create `yj901010/knowledge-brain` as public, use `main`, and apply Apache-2.0 only after explicit approval of the reviewed source and license. Keep existing dependency/data notices. This best matches the stated open-source direction. | The source, tests, documentation and included DRAFT authoring pack become publicly readable. Publication cannot be treated as reversible merely by later hiding the repository. Review the exact archive first. |
| Private source with account-policy confirmation | Create the same repository as private and retain the current license status until an explicit license decision. Check the actual account plan and protection/billing controls before hosted release execution. | Private environments require Pro, Team or Enterprise. Free/Pro/Team required-reviewer rules are public-only, so a private repository must not silently receive a weaker or unavailable gate. |

GitHub documents the plan restrictions in
[Managing environments for deployment](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).
Recheck the actual account's controls before choosing the private route; no plan
upgrade or substitute approval policy is included in this proposal.

Standard GitHub-hosted runners are free for public repositories. Private compute
and storage depend on the account's allowances, and additional usage can be
billed to the repository owner. This does not establish unlimited artifact or
registry storage. No paid runner, spending-limit increase or subscription change
is authorized. See [GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions).

Apache-2.0 is a proposed project-code choice, not a license already applied or a
blanket clearance for bundled infrastructure or medical data. The exact license
file and attribution must be included in the final reviewed source if selected.
The 21 short factual drafts remain marked DRAFT and independently unreviewed;
publicly hosting their authoring files does not merge them into canonical public
Knowledge, confer clinical verification, or relicense their cited sources.

## Local source review before approval

`Prepare-SourceReview.py` creates a new ignored local directory containing an
immutable `source.tar.gz` and a success `manifest.json`. The manifest lists every
relative path, byte count and SHA256, plus the complete source-tree/archive hashes.
It selects current working-tree files through Git's cached/untracked inventory
with standard ignore rules; it does not substitute staged blobs for current files.
The `mode` field is a portable archive permission (0644 or 0755) derived from the
host's executable bits, not the staged Git mode. Windows may infer those bits from
file extensions. A later source commit must inspect its own Git file modes.

```powershell
python scripts/Prepare-SourceReview.py `
  --directory .local/v1-final-audit/source-review-<unique-review-id>
```

Existing output is never overwritten. Operational paths, actual private PEM
material, keystores, credential kubeconfigs, linked/reparse paths, root escapes
and oversized/archived runtime artifacts fail closed even when tracked. Git-ignored
untracked files are excluded. Ordinary source, CI-only disposable values and code
that generates keys remain inspectable. Git selection, file identities and hashes
are rechecked before/during/after archive creation. A failed attempt cannot issue
a success manifest; retain partial output for diagnosis.

This bounded guard is not complete secret detection or license clearance. Review
the actual manifest and selected files before publication. Changes after a review
require a new directory, archive and hash, with the actual difference checked
against the approved scope. Already authorized CI fixes and the selected license
do not require repeated approval; new exposure, license, cost or scope decisions
do. Never publish a changed tree under a stale review fingerprint.
The tool makes no commit, remote call, deletion or runtime change.

Before requesting approval, record the actual archive directory, source-tree SHA,
archive SHA and file count here or in the linked final review receipt. Those values
are intentionally pending until all concurrent source changes are finished. This
document alone is not a completed source snapshot or permission to publish.

## One proposed remote execution batch

After the repository visibility, exact source archive, project-code license and
hosted execution scope are approved together:

1. Recheck ownership/name availability. Create the selected repository only if it
   does not already exist; never replace or overwrite an existing repository.
2. Prepare the approved source/license commit, compare its tree to the reviewed
   manifest, connect the remote and push `main`. The push starts source CI, so
   hosted Actions authorization must precede this step.
3. Configure the available default-branch protections, required source checks and
   `ghcr-release` environment. Restrict release execution to the selected protected
   default branch. The proposed environment approver is `yj901010`; enabling
   prevent-self-review requires a different eligible approver and must not be
   simulated or assumed. Application public-Knowledge reviewer roles are unrelated.
4. Run the existing source workflow and inspect all results, including explicitly
   skipped external Kafka/storage cases. Fix failures and obtain a new source
   review if the approved tree changes. Do not label skipped integration paths as
   executed merely because a hosted job is green.
5. Dispatch the existing release workflow with the approved exact source commit.
   Build and scan all 13 archives; publish only successful same-archive results.
   Retain real GHCR manifest digests and a complete same-source release lock.
6. Prepare the resulting GitOps values and rendered adoption diffs for inspection.
   Keep the existing controller's application/data authority unchanged. This batch
   does not sync or adopt current applications, modify PVCs, or deploy production.

GHCR packages initially default to **private**, independently of repository
visibility. The workflow uses its scoped `GITHUB_TOKEN`; changing a package to
public requires a separate explicit visibility decision. Existing unrelated
packages must not be overwritten. See
[Working with the Container registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).
Private cluster pulls will need separately provisioned, appropriately scoped
access; the workflow token is not a permanent cluster credential.

The final remote receipt must identify the repository URL/visibility, approved
source commit/tree, actual protection settings, hosted run IDs/results, all 13
GHCR digests and package visibility, release lock and remaining adoption diff.
Do not substitute the local probe or prepared YAML for those results.

## Approval record to complete

- Repository: `yj901010/knowledge-brain`, or an explicitly selected existing URL.
- Visibility: public-source proposal, or private with confirmed account policy.
- Exact reviewed source-tree/archive hashes: pending the final local snapshot.
- Original project-code license: proposed Apache-2.0, or explicitly leave unselected.
- Hosted Actions and 13 initially private GHCR package publications: explicit scope.
- Environment approver and supported protection policy: verify and record.
- Paid usage, public GHCR visibility and existing-app adoption: not included.

No remote change, license application or source snapshot is performed by writing
this review. [Delivery runbook](delivery.md) and [current scope](v1-completion.md)
remain authoritative for the execution and remaining V1 work.
