# Delivery runbook

This package implements the existing GitHub Actions → independent service tests/builds → vulnerability gate → GHCR → reviewed GitOps → Argo CD direction. It does not alter service ownership, databases, Outbox/Connect publication, privacy or domain contracts. Local and future production use the same service Helm chart. Production environment configuration and HA remain separate work.

## Source validation and release

`.github/workflows/ci.yml` runs a pinned Node24.10.0 static frontend build, TypeScript checks, generated license notices/CSP and commit-bound asset hashes. The thirteen-service matrix compiles/tests Java25 with the reviewed Maven/JDK image. Each job has its own ephemeral PostgreSQL instance and owner database/user. Gateway restores the exact source-commit frontend artifact. Surefire evidence distinguishes executed checks from skipped external Kafka/storage cases. Protobuf modules compile in the Maven reactor; this does not claim a live Registry compatibility or CDC test in hosted CI.

Delivery tests validate immutable image digests, complete same-source releases, asset tampering, stale/concurrent promotions, exact Git configuration contents, namespace authority and existing tag/telemetry Helm behavior. Actionlint checks both workflows. CI has only `contents: read`, no repository credentials persisted by checkout and no publishing secrets for pull requests. Actions use full reviewed commit SHAs.

`Build scanned release` is a manual workflow dispatched from the repository default branch. Input is an exact40-character commit reachable from that branch. It must pass the reusable CI before thirteen independent Jib archives are built. Trivy scans the same archive before publication: any HIGH/CRITICAL result or scanner error stops that image. No `ignore-unfixed` or vulnerability exception is configured. The archive and report hashes must still match when publishing. Only the image job has `packages: write`; its token is supplied only to the publish step and Docker credentials live in a temporary directory. No Kubernetes credentials are present in Actions.

Successful pushes produce registry manifest digests, not local Docker config IDs. The final release lock requires all13 unique images from the same source commit and successful scans. A partially failed matrix may leave successfully scanned SHA tags in GHCR, but cannot produce a complete release lock. The workflow creates reviewable artifacts only; it does not push GitOps commits or sync a cluster.

Before remote activation, choose/connect the GitHub repository, review the initial source, configure branch protection and required CI checks, and restrict the `ghcr-release` environment to the protected default branch with the intended reviewer policy. Confirm GHCR package visibility explicitly. No remote repository, source publication, GHCR package or hosted workflow run was created by this local package. Push CI currently targets `main`; adjust that filter if the chosen default branch differs. PR and manual validation are also available.

## Prepare a digest-pinned GitOps release

Use a real successful `release-lock.json` artifact. Source and configuration commits are distinct. Stage values first, commit/review them, then generate Applications pinned to that existing configuration commit:

```powershell
python scripts/ci/gitops.py --release <release-lock.json> --stage-only
# Review and commit infra/gitops/releases/local/<release-id>/ in the selected repository.
python scripts/ci/gitops.py --release <release-lock.json> `
  --repository-url https://github.com/<owner>/<repository> `
  --gitops-revision <existing-configuration-commit-sha>
```

The second command verifies that the exact Git commit contains all staged release JSON files. `--git-checkout` selects a separate inspected checkout if needed. Review/commit `infra/gitops/applications.json` and the environment pointer after generation. No script creates a source commit, pushes it or applies these Applications.

First promotion expects an absent environment pointer. Later promotion requires `--previous-sha256 <SHA256-of-current-infra/gitops/environments/local/release.json>`. A concurrent writer or changed pointer fails. Versioned release files reject modification. Roll back by preparing the previously successful complete release lock with the current pointer hash and its known configuration commit; review and manually sync. Never move image tags to simulate rollback.

All13 Applications preserve their service release names, existing service/local values and telemetry overlay. They allow only Deployment/Service/NetworkPolicy, use exact configuration commits and OCI digests, reference an operator-provisioned `brain-ghcr-pull` Secret, have no automatic sync/prune/deletion finalizer, and mark resources `Prune=false,Delete=false`. Existing local applications are not adopted by this package. Before adoption, explicitly inspect every rendered diff, install repository/GHCR access without committing credentials, and review the destination RoleBinding. The installed controller currently cannot modify `brain-apps` or data PVCs. Production generation fails until a separately reviewed production overlay exists; local URLs/certificates are not production configuration.

## Local controller and reproducible drill

```powershell
python -m pip install --require-hashes -r scripts/ci/requirements.txt
python scripts/Install-LocalDelivery.py
python scripts/Run-DeliveryDrill.py
python scripts/Run-DeliveryCI.py
python scripts/Verify-DeliveryFrontend.py
```

These commands require the existing `k3d-knowledge-brain` context. Installer checks the vendored Argo CD3.6.1 manifest and CRD hashes. Full server/repository/controller/RBAC components run in `brain-delivery`; optional upstream Dex/ApplicationSet/notification replicas are zero locally. Active images are digest-pinned. Built-in server TLS/auth remains enabled, with no host listener or public ingress. The initial admin credential is a Kubernetes Secret; never commit or print it into evidence. Production SSO, HA, external HTTPS and monitoring integration need their own configuration.

The controller's in-cluster credentials watch only `brain-delivery-probe`. Its write Role covers the probe's Deployment/Service/NetworkPolicy and events; it has no cluster write role, domain-namespace write binding, PVC deletion permission or static service-account token. Namespace-install roles retain normal control-plane access within `brain-delivery`.

The drill creates an isolated local Git repository containing only the service chart and non-sensitive probe values. A read-only, network-restricted HTTP fixture serves it inside the cluster. Its three exact commits use the cached real Gateway OCI manifest with the API disabled and no domain database, credentials or telemetry. The drill checks initial Git sync/health, detected out-of-band drift, manual repair, a new commit and a Git-revert rollback, retaining one Deployment UID. The fixture is a local test repository, not a GitHub deployment or source publication. The application stays manual and the final probe remains Ready for inspection.

Backend CI uses temporary owner databases in an `emptyDir`, never the canonical PostgreSQL service. It starts asynchronously; inspect the Job named in `.local/delivery/ci-job.json` and collect its logs before the24-hour TTL. Source ConfigMaps belong to that Job. Frontend CI uses an isolated Git fixture and the same pinned Node build; its commit is not a commit of the user's source repository. Results live under `.local/delivery/` and contain no production credentials. Existing local app/UI forwards remain separate from Argo CD.

## Dependencies and limits

`infra/dependencies/delivery-tools.json` records exact action commits, image digests, tool archive hashes, upstream URLs and license file hashes. Argo CD/Helm/Trivy use Apache2.0; actionlint/Actions/PyYAML use MIT. The unmodified upstream Redis8.10.1 image is exclusively Argo CD's internal ephemeral cache; AGPL3.0-only is the selected upstream license, with complete license text and corresponding source URL recorded. It does not replace the platform's planned Valkey cache or any canonical store. Disabled optional images require review before enabling them.

Scanner detections are release-blocking inventory results, not proof that a particular vulnerability is exploitable in this deployment. The historical initial Gateway scan and delivery prerequisites are recorded in `delivery-validation.txt`. The later13-service local remediation/rollout is documented in [Java runtime maintenance](java-runtime.md) and current status; detailed security evidence is retained in the managed repository collection. No security exception, public release, full13-image new release, production readiness, live CDC rerun or whole-cluster backup/restore is implied by this delivery drill.
