# Local Windows/WSL resource recovery

## Verified history — 2026-11-01

At02:46:35UTC (11:46:35KST), Docker’s kernel logged a page-allocation failure with4,182,175RAM pages (~16GiB), ~95MiB free RAM and no free swap out of4GiB. Windows still had ~7.5GiB free on its32GiB host. Docker info returned500 and Kubernetes readyz timed out. The examined logs establish memory pressure, not an OOM-killed process.

The user explicitly approved20GB RAM/8GB swap and the full WSL/Docker restart. `C:/Users/이영재/.wslconfig` was created with exactly:

```ini
[wsl2]
memory=20GB
swap=8GB
```

Runtime measurement: MemTotal20,481,948KiB, SwapTotal8,388,608KiB, SwapFree8,388,608KiB. This is a maximum for all WSL2 distributions, not a permanent20GB reservation. Preserve the existing user configuration on future edits.

Docker startup initially failed on one stale zero-byte runtime socket. Its containing runtime-only directory was moved intact to `C:/Users/이영재/AppData/Local/Docker/run.recovery-0b89127a935642aeb64bf4934ff0a0bc`; no volume or VHDX was modified by this action. Docker then briefly answered with Server28.0.1/cgroup2 and zero images/containers.

Docker GUI logs record “Reset to factory defaults” at03:24:47.881724600UTC (12:24:47KST), followed by distribution destruction and new VHDX creation. The agent did not issue the reset. The user confirmed no backup and approved a fresh local environment without old-data restoration. The old kubeconfig/image/provenance metadata is retained in ignored `.local/recovery-pre-reset-20261101`; it is historical metadata, not a database backup. Original user data has not been restored.

## Resolved host blocker — historical evidence

At03:33:16UTC the empty engine entered Resource Saver and froze its cgroups. Subsequent API requests reported no route to the internal engine. This timing is consistent with the documented Docker/WSL Resource Saver hang, but does not prove the sole cause. Docker’s official4.42.1 notes describe disabling Resource Saver on WSL2 to prevent command hangs.

With Docker stopped, only `UseResourceSaver` was set to false in `C:/Users/이영재/AppData/Roaming/Docker/settings-store.json`. Original settings remain in adjacent `settings-store.before-wsl-recovery-20261101.json`. The global20GB/8GB configuration remains unchanged. Direct WslService restart failed with Windows access denied; after the user reported performing it as administrator, WSL list still exceeded12seconds and Docker reported Unexpected WSL error. Windows restart was requested. On resumption, WSL list and Linux/cgroup2 Docker API requests answered successfully; the approved fresh cluster was then created. Fresh runtime verification and the GitHub bundle are complete; see `github-work-bundle-validation.txt`. Original data restoration is not claimed.

The cluster startup script now bounds its read-only Docker preflight (default30seconds, configurable1..120), checks actual Linux/cgroup2 fields, and stops before k3d when the CLI hangs or returns empty formatted fields even with exit0. Five isolated executable-fixture cases passed: unavailable engine, Windows engine, cgroup1, timeout and valid Linux/cgroup2 workflow. No real cluster was used for those fixture checks.

## Recovery procedure for a future restart

Save open work, use Windows Restart, then open Docker Desktop. Check `wsl --list --verbose` returns promptly and `docker info --format '{{.OSType}}|{{.CgroupVersion}}'` returns `linux|2`. A running GUI alone is insufficient. Confirm Resource Saver remains disabled. Do not perform another factory reset as a recovery step.

Run `scripts/Start-LocalCluster.ps1` with the existing pinned k3d/Kubectl paths. It writes project-only `.local/kubeconfig.yaml` and reuses an existing cluster on retry. Then rebuild the previously implemented platform from existing Helm charts/values and provisioners: PostgreSQL per-owner databases, TLS/credentials, Strimzi/Kafka, Keycloak/login, Registry/immutable schemas, MinIO, OpenSearch, Cassandra, ClickHouse, all13 application services and10 scoped Connect connectors. Old local-only images must be rebuilt/imported on both new nodes. No previous account/domain fact/source/offset is recreated from test logs or claimed restored.

Run heavy builds one at a time. Let canonical projections finish before the Gateway frontend build and final browser suite. Record all fresh deployment/validation provenance separately from preserved pre-reset evidence. Central observability, Valkey and Argo CD remain unfinished work in the project; this recovery does not claim them deployed.

Sources: [Microsoft global WSL configuration](https://learn.microsoft.com/en-us/windows/wsl/wsl-config), [Docker WSL resource limits](https://docs.docker.com/desktop/features/wsl/best-practices/#limit-cpu-and-memory-usage), [Docker4.42.1 release notes](https://docs.docker.com/desktop/release-notes/#4421), [upstream Resource Saver hang report](https://github.com/docker/for-win/issues/14656).

Fresh rebuild verified: 2026-11-01T06:05:03.838950+00:00. Two Ready nodes,14 Ready applications,10 running connectors,76 domain schemas,363 fresh backend checks and17 browser scenarios. Temporary image builders were cleaned after provenance capture.

Subsequent current-dataset recovery milestone (2026-11-01): an encrypted backup outside Docker was restored into a separate k3d cluster and verified against7 volume hashes and14 DBs/282 tables, existing authentication/history/binary bytes and a new event. Source UID/PVCs remained intact and the source runtime is Ready again. If Docker storage is lost in a future incident, prefer the latest COMPLETE bundle and separate recovery key using `local-backup-recovery.md`; the earlier pre-reset dataset remains unavailable. Off-host copy is still an operator follow-up.
