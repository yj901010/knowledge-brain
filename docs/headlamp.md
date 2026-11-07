# Local Kubernetes web UI: Headlamp

Headlamp is an operator UI added alongside the existing application services. AGENTS.md / PROJECT.md ownership, database boundaries, guest identity, Kafka/Outbox and deployment architecture remain unchanged. This UI uses Kubernetes service-account authentication and is separate from application guest sessions and Keycloak users. It does not expose Personal Brain notes as public application content.

## Browser access

Open https://localhost:8444 while the local port-forward is running in your own PowerShell terminal. This Codex execution session could bind a port but its localhost TCP/browser connection timed out even after network permission was granted; its clipboard also was unavailable. Cluster HTTPS/proxy/RBAC checks passed. The access helpers below run in your user session and are therefore required for browser access. It binds only 127.0.0.1, not the LAN. The service is ClusterIP with no Ingress, NodePort or LoadBalancer. TLS uses the existing local CA `.local/guest-api-ca.crt` and a separate Headlamp server leaf. Trust that CA in the browser as for the local Gateway; the CA private key stays in brain-data and is never mounted in Headlamp.

To start/restart the forward in a terminal:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\dev\knowledge-brain\scripts\Start-LocalHeadlamp.ps1
```

Use a second terminal to copy a one-hour read-only login token to your Windows clipboard:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\dev\knowledge-brain\scripts\Copy-LocalHeadlampLogin.ps1
```

If clipboard access is unavailable (for example from a noninteractive automation session), run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\dev\knowledge-brain\scripts\Get-LocalHeadlampLogin.ps1` in your own terminal. This explicit fallback prints the short-lived token for you to copy; do not include it in screenshots, chat messages or repository files.

Paste into the Headlamp token login field. The script does not display the token or save it to disk. Re-run after expiry and log in again. Treat the clipboard as a credential, paste only into this local UI, and log out when finished. Session TTL is also one hour. No permanent service-account token Secret is created. Token creation is an operator action; the viewer token itself cannot create another token.

The cluster context is `knowledge-brain`. In Workloads -> Pods / Deployments, choose `brain-apps` for application services, `brain-data` for PostgreSQL/MinIO/Keycloak, and `brain-events` for Kafka/Connect/Registry. Logs and events help diagnose startup failures; Nodes and Metrics show the existing metrics-server data. Headlamp uses Kubernetes RBAC even if an edit action appears in the UI.

## Permissions

`brain-headlamp-viewer` in brain-ops is bound to the built-in `view` ClusterRole plus `brain-headlamp-observer`. The additional role grants only get/list/watch for nodes, namespaces, persistent volumes, storage classes/CSI metadata, metrics, CRD definitions and selected Strimzi Kafka resources. Built-in view allows ordinary workload/service/event/configuration reads and Pod logs, but excludes Secret values. This is an operator view across namespaces, so configuration, logs and resource metadata are visible; application Space authorization is a separate boundary.

Verified denied: Secret reads, Pod creation/deletion/exec, Deployment patching and service-account token issuance. There is no cluster-admin binding for either Headlamp or its viewer. The backend's own account `brain-headlamp` has no workload role binding. `unsafeUseServiceAccountToken` is false: each UI request uses the user's token, not automatic backend impersonation. Helm management, cluster inventory, downloaded plugins and bundled optional plugins are disabled.

## Reproducible install and dependency review

Pinned app/chart: 0.45.0. Image and chart SHA-256 are in `infra/dependencies/headlamp.json`; no latest image tag. Helm values: `infra/environments/local/headlamp-values.yaml`; RBAC/namespace/NetworkPolicy: `infra/headlamp/access.yaml`. Original chart is downloaded from the official Kubernetes SIGs release and hash checked before use. Namespace brain-ops enforces restricted Pod Security. The container runs nonroot, with dropped capabilities, no privilege escalation, read-only root filesystem and a bounded 128 MiB writable temporary volume. Initial 32 MiB temporary sizing was insufficient for ~32.4 MiB extracted UI assets and was corrected before acceptance. Requests are 50m CPU / 96 MiB memory; limits 500m / 384 MiB. No domain database or persistent UI storage is added.

Reinstall with an existing Helm on PATH:

```powershell
python .\scripts\Install-LocalHeadlamp.py
```

If needed, supply `--helm C:\path\to\helm.exe`. A host Python environment with the existing cryptography dependency is required for the local certificate provisioning script. The installer is limited to the k3d-knowledge-brain context. CA rotation remains an explicit operator action; leaf certificates renew under the existing CA expiry boundary.

Upstream project and exact release: https://github.com/kubernetes-sigs/headlamp/releases/tag/v0.45.0 . License: Apache-2.0, https://github.com/kubernetes-sigs/headlamp/blob/v0.45.0/LICENSE . Commercial use and redistribution are permitted subject to license and notice/attribution obligations; preserve upstream license/NOTICE with redistributed software. The image is an unmodified separate operator tool, not a new application runtime dependency. Official installation guide: https://headlamp.dev/docs/latest/installation/in-cluster/ .

## Actual cluster validation

See `docs/headlamp-runtime-validation.txt`. Run `python .\scripts\Run-HeadlampValidation.py` for a verifier Job with the same viewer account and a short-lived projected token. It checks the trusted HTTPS HTML, authenticating Headlamp proxy, two nodes, five application deployments, Pod logs, node metrics and real authorization denials. Resource contents and tokens are never printed. No application data or production resources are modified by the verification.

## TLS reset / lost connection to Pod

On 2026-10-07 at 20:58:56 local time, the server recorded `client sent an HTTP request to an HTTPS server` from the same port shown in the subsequent kubectl reset. The Pod remained Ready with zero restarts. This is a protocol mismatch, not evidence that port 8444 is already occupied. Always use `https://localhost:8444/`, not `http://`. If the tunnel stopped, restart Start-LocalHeadlamp.ps1.

The helper now preserves kubectl diagnostics and reconnects up to five times after connection loss. Actual bind conflicts fail with a separate message. Ctrl+C/normal native exit stops it. Use `-MaximumReconnectAttempts 0` to disable retries or `-Port 8446` for a different local port (update the browser URL accordingly). No TLS validation, RBAC or network exposure was weakened.
