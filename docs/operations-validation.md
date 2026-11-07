# Local API and current-stack recovery validation

This procedure targets the existing32GB development host,20GB WSL limit and
unchanged13-service architecture. It establishes bounded local evidence. Production
multi-zone HA, online PITR and site-level RTO/RPO are the future targets in
`PROJECT.md` section24, not results inferred from this single-host drill.

## Sustained API profile

```powershell
python scripts/Run-LocalLoadValidation.py --seconds 60
python scripts/Run-LocalLoadValidation.py --seconds 600
```

The pinned Python Job uses the standard library and verified project CA. It creates
two fresh private guest Spaces with four Notes/three Wiki links each. Its fixed
ten-request cycle covers canonical Note reads40%, private graph20%, existing public
search20%, public graph10% and another actor's forbidden Note10%. Each closed-loop
reader starts at most one request per second, with2/4/8 readers over20/60/20% of the
profile. This fixed cycle is not randomized, an open-loop arrival model or a search
for maximum throughput. One separate writer alternates actors every10 seconds,
uses the current ETag and creates explicit Note revisions through the owning API.

Checks include exact allowed/forbidden statuses, private canaries absent from public
responses, no foreign graph nodes, fresh revocation, unchanged second-actor access,
and every final Note version appearing exactly once through real Outbox/Connect/
Kafka/Activity. Fixed metrics sample13 JVM services,14 Ready apps and10 RUNNING
connector/tasks throughout. Cookies/bodies/IDs are not included in reports. The Job
mounts only the CA entry, with no Kubernetes token or telemetry private key.

The600-second run on2026-11-07 KST completed2,640 reads and60 canonical writes with
zero unexpected outcomes. Each actor's31 versions reached Activity with unique
event IDs. The largest phase/route p95 was714.61ms; write p95 was20.23ms. Full
per-phase/route counts and percentiles remain in the source-bound Job receipt.
Do not combine unlike routes, imply that forbidden404s are failures, or attribute
this result to a Java/cache optimization without an A/B comparison.

This is in-cluster HTTPS with persistent connections, small private fixtures and a
bounded existing Development corpus. Browser/rendering, external networks, uploads,
all collaboration queries and larger datasets are outside this profile. Earlier
Windows localhost client samples used a different connection boundary; their roughly
2-second duration is not a before/after measurement of this workload. No production
SLA/capacity claim or unmeasured alert threshold is introduced.

## Current-stack backup and restore

Follow [the encrypted backup runbook](local-backup-recovery.md). Inventory includes
the five original platform namespaces plus existing local delivery/probe/Argo
namespaces, their CRDs/Application/AppProject objects, exact referenced RBAC,
workloads and credentials. It does not capture unrelated tenants. The GitOps
reconciler is stopped before its workloads so a queued sync cannot race the cold
snapshot. Source and clone retain separate names, API ports, networks and UIDs.

Before writing a backup, every selected Linux/amd64 image root, manifest, config
and layer must exist in containerd. Running an already-unpacked Pod does not prove
its exportable image content remains complete. A missing382-byte Tempo layer was
found and restored from the same immutable official image; no workload version or
Pod rollout was requested. Runtime layers may never be skipped. OCI-only archives
omit the redundant Docker-compatible manifest; all required selected content remains
mandatory. Fifteen recovery regression checks cover these guards and prior
authentication, traversal, target, overwrite and resume boundaries.

The latest authenticated COMPLETE bundle contains13 cold volumes,39 running workload
images,18,304 per-file hashes and all14 PostgreSQL DBs/292 table fingerprints,
including the current observability and local GitOps stack. It is6,690,844,867
encrypted bytes outside Docker. The earlier failed image-export attempt has no
COMPLETE marker and remains retained separately; it is not a recovery candidate.

`Run-LocalRecoveryValidation.py --mode inspect` reuses the existing registered and
guest credentials from the private fixture Secret. It checks original v2/v1 Note
history, Concept links, sealed object SHA256 and foreign denial without changing
that Note. Normal login creates a fresh session/guest claim. Failure call logs can
contain typed credentials and stay inside the private Job rather than host output.

Actual clone verification must authenticate every archive before target writes,
match all volume and per-database table fingerprints before applications start,
verify79 Registry artifacts/rules and10 connector/tasks, reuse the old login/guest/
binary fixture, read restored public discovery, write isolated v3 and observe CDC.
The clone must then stop, the source must return with original workload/PVC UIDs
and specs, five trusted-CA HTTPS forwards must return, and source inspection must
still see v2. Final results belong in the companion validation record only after
these checks pass. An encrypted bundle alone is not actual-restore evidence.

Keep the key separate from the encrypted bundle. This host-disk copy protects
against Docker reset, not loss of the same physical Windows disk. No off-host copy,
automatic deletion, schedule, external notification or production guarantee is
claimed. Ordinary failures retain journals and resume the original source; resume
never replaces a running/changed target. No canonical reset or public publication
is part of this procedure.

Actual current-stack restore, isolated new writes and source return are now verified in [the result](operations-validation.txt). The SQL table-result ordering defect was diagnosed as identical full rows in a different order; comparison preserves every table, count and content hash. A separate current operator kit accompanies the immutable data bundle.

The separate encrypted operator kit is 3,920,884 bytes. Authenticated extraction
into a new directory reproduced all 2,546 exported source files, and all 15
recovery boundary tests passed from the extracted copy. The kit contains the
corrected operator scripts; final validation records were appended after that
snapshot. The encrypted data bundle and its embedded source archive remain intact.
The private hash index is `.local/operations-evidence-index.json`.

This package adds no dependency or dataset. The load runner uses the Python
standard library; recovery reuses the previously reviewed `cryptography==44.0.3`
operator dependency and existing pinned validation images. No validation library
is added to the application runtime.
