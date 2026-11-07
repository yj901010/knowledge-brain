# Private OpenSearch read-model foundation

OpenSearch 3.9.0 is installed as release brain-opensearch in brain-data. Its single
local StatefulSet node stores data on a 10 Gi PVC. This is derived storage, never
Content's canonical note database. The container image is pinned by digest:
`opensearchproject/opensearch:3.9.0@sha256:adfa61f85025d06b4aeb562e7e74fde7e31c437039c93c3862c17e9acebd6c7c`.

## Security and runtime

Only `https://brain-opensearch.brain-data.svc:9200` is used by clients. There is no
Ingress, NodePort, browser forward or Dashboards deployment. REST requires a valid
client certificate; transport also uses TLS. Certificates are signed by the existing
local CA, whose private key remains in its original Secret. No private keys are exported.

Separate identities:

- brain-note-reader: search only on brain-notes-v1; no direct document fetch or writes.
- brain-note-projector: index/get on brain-notes-v1, with the internal bulk permissions
  required by OpenSearch single-document indexing; no search, delete or other-index creation.
- brain-search-admin: initial configuration/index operator Jobs only, never an app mount.
- brain-opensearch: transport node identity and cluster-health readiness only.

Reader/projector certificates are separate Secrets in brain-data (test Jobs) and
brain-apps (future Brain Query deployment). Only admin and node certificates stay
in brain-data. NetworkPolicy accepts Brain Query and specifically labelled local
client/operator Jobs; node transport accepts only OpenSearch peers. Unlabelled Pods
are denied even with a valid reader certificate. Search credentials are internal;
this layer does not substitute for authoritative end-user Space permission checks.

Security configuration has no internal users/demo passwords. The node starts directly,
without the image's demo installer. The container runs as UID 1000, drops capabilities,
uses RuntimeDefault seccomp and a read-only root filesystem. A non-root init container
copies runtime configuration to bounded emptyDir because OpenSearch must create its
keystore during startup. Data, logs and temporary files have separate mounts.
TLS is verified in both the adapter and initialization tooling; no insecure TLS flags.
Request bodies are excluded from security audit logging; compliance payload logging is disabled.

The ClusterIP publishes not-yet-ready endpoints so the confined mTLS administrator
can initialize security before authenticated readiness succeeds. This does not bypass
authentication or expose an external port. Readiness checks authenticated cluster health;
liveness checks the listening port. Configuration checksum changes roll the StatefulSet.

Local heap is 512 MiB, memory request 1 GiB and limit 1536 MiB. Observed pod RSS was
about 915-1008 MiB at light test load. This is not a load test or complete 32 GB platform
capacity claim. Local shard/replica settings are 1/0; production redundancy requires
separate environment values and operational planning. Neither a PVC size request nor
these tests establish physical storage quota, backups or site-level HA.

## Commands

From C:\dev\knowledge-brain, with the existing Helm executable:

```powershell
python .\scripts\Install-LocalOpenSearch.py --helm 'C:\path\to\helm.exe'
python .\scripts\Run-BackendValidation.py --brain-query --search-storage
python .\scripts\Run-OpenSearchValidation.py
python .\scripts\Run-OpenSearchValidation.py --network-denied
```

All operator scripts reject a non-local kubeconfig context. The installer applies
Helm values, provisions scoped leaf certificates, runs the administrator initialization
Job and checks actual readiness. Existing leaf certificates are reused until near expiry.
Initializing an existing index preserves its data. Security policy updates intentionally
replace the fixed roles with the repository configuration.

Validation runners print their Job name; check that Job's Complete condition and PASS
output. A denied-network test must be paired with a successful allowed-client control
Job and a Ready OpenSearch Pod, so server unavailability cannot masquerade as isolation.
K3s may reject a blocked connection immediately rather than dropping it until timeout.

`--restart` on Run-OpenSearchValidation.py is an operator-driven failure test: wait for
RESTART_READY, record the current OpenSearch Pod UID, replace only that Pod, wait for a
different UID to become Ready, then create `/state/resume` in that validation Job Pod.
Without this handshake the verifier fails rather than claiming restart success. The
recorded local run performed this actual replacement; no PVC was deleted.

## Scope

Brain Query's NoteSearchClient uses bounded-response JDK HTTPS with mTLS, timeouts,
no redirects and a fixed note index. Canonical revision numbers drive strict external
versioning. On conflict it fetches the stored document: an identical same-version
snapshot is a duplicate, a higher same-Space version supersedes the event, and conflicting
same-version or cross-Space snapshots fail closed. The query accepts plain text only,
filters Space/PRIVATE/ACTIVE, excludes retired tombstones and rejects partial search results.

Brain Query now projects live private Note events with a durable Inbox/retry queue;
see [note-projection.md](note-projection.md). No end-user search route, Gateway route
or Content canonical rebuild API is implemented yet. The next implementation adds
canonical rebuild and Space-authorized search APIs. Graph projections remain separate.

Evidence: [note-search-runtime-validation.txt](note-search-runtime-validation.txt).
License review: [opensearch-dependency-review.txt](opensearch-dependency-review.txt).
