# Local observability

The13 Java services export OpenTelemetry over mutual-TLS OTLP HTTP/protobuf to a
private Collector. Prometheus stores metrics, Loki stores sanitized operational
logs, Tempo stores sampled traces, and Grafana presents18 provisioned panels.
Alertmanager receives7 platform alert rules. The existing Kafka/Connect/Outbox
path and per-service data ownership are unchanged.

## Open the dashboard

Open <https://localhost:8446/d/brain-platform>. Username: `brain-admin`.
In a separate PowerShell terminal, copy the generated local password:

```powershell
cd C:\dev\knowledge-brain
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Copy-LocalMonitoringLogin.ps1
```

Paste into the Grafana login form. The helper copies the Secret to your clipboard
without printing it. It does not change the password. This is a separate operator
account; product guest/Keycloak users are not automatically Grafana users.

The forward is running at the completion of this milestone. If it ends after a
Pod/cluster restart, keep this command running in its own terminal:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-LocalMonitoring.ps1
```

This binds only127.0.0.1:8446. Grafana serves HTTPS using the existing local project
CA. Follow the existing local-CA trust process; do not disable TLS verification.
Grafana signup/anonymous access and usage reporting are disabled. No public Ingress
or product Gateway route exposes any monitoring backend. Kubernetes operators able
to read Secrets/port-forward remain privileged.

## Components and storage

| Component | Private endpoint | Persistent data | Local retention |
| --- | --- | --- | --- |
| Collector | brain-otel-collector:4318, HTTPS + client certificate | 1Gi sanitized export queue | bounded queues/retries |
| Prometheus | brain-prometheus:9090 | 1Gi TSDB | 24h or768MB, whichever is reached first |
| Alertmanager | brain-alertmanager:9093 | 256Mi alert state | local alert state |
| Loki | brain-loki:3100 | 2Gi chunks/index | 24h, asynchronous compaction |
| Tempo | brain-tempo:3200 / OTLP HTTP4318 | 2Gi WAL/blocks | 6h, asynchronous compaction |
| Grafana | brain-grafana:3000, HTTPS | 1Gi configuration/database | local operator state |
| Platform observer | brain-platform-observer:9102 | none | current metrics only |

All endpoints above are in `brain-observability`. Kafka exporter is the existing
Strimzi-managed Deployment and an explicit Service in `brain-events`, port9404.
It exports bounded `brain-*` consumer-group lag for the ten domain topic prefixes.
Loki/Tempo rely on private network boundaries rather than backend user accounts;
only authorized monitoring/validation Pods can enter that namespace's services.

The chart caps resources and uses one replica per component for the32GB local
machine. This preserves the intended monitoring components but provides no HA.
Host memory metrics describe the single Linux/WSL host, not the Windows process
total or a sum of two k3d nodes. Short retention is intentional; watch actual disk
use, since PVC requests do not provide a hard local-path filesystem quota.

## Instrumentation and correlation

The shared service chart defaults `observability.enabled` to false. The local
`observed-service-values.yaml` overlay enables the digest-pinned official Java
agent2.33.1 for all13 service releases, with `environmentName: local` and10% parent
based trace sampling. Metrics export every15s. JVM/native HTTP metrics and existing
bounded `brain.*`/Hikari metrics are retained; duplicate Micrometer JVM/HTTP series
are filtered. Request/error/duration, heap/GC/CPU, DB pool pressure, projection
retries, app readiness/restarts,10 CDC tasks, Kafka lag and memory headroom are
visible. The separate login workload is monitored for readiness.

The Gateway uses its active OpenTelemetry SpanContext to populate the existing
`X-Trace-Id` when a browser request has no valid incoming trace context. Valid
remote parents retain their existing trace identifier. No-agent behavior remains
the existing fallback. Only Gateway required a new image; other12 service images
were instrumented through the common Helm init container.

Actual HTTP spans connect Gateway, Identity, Space and Content in the verified
flow. Existing Outbox events continue to carry the stable domain `traceId`, and
consumer logs expose only a validated32hex `domain_trace_id`. Grafana trace-to-log
links match that field or the automatic OTLP trace identifier. This gives genuine
HTTP spans plus asynchronous event/log correlation. The existing event envelope
does not carry a parent span identifier: this milestone does not invent a
parent-child HTTP-to-Kafka span tree or change published event contracts.

Telemetry export is best effort, with bounded agent queues and short timeouts.
Collector persistent queues hold only sanitized records. A Collector outage does
not become a domain transaction dependency; the actual outage test stored/read a
Note and subsequently observed its Outbox-to-Activity projection. Telemetry may be
lost during prolonged pressure or downtime; it is not a canonical audit history.

## Data minimization and authority

The Collector removes log bodies, exception details, SQL, URLs/queries, headers,
actor/Space IDs and arbitrary attributes before batching or persistence. Logs have
the body `operational log`, a bounded component name where recognized, service
identity and trace correlation. Trace names use HTTP method plus templated route
or `operation`; status messages are cleared, span events dropped, and spans with
links dropped. Resource/scope/metric attributes have explicit allowlists. Trace
IDs remain operational correlation identifiers with short retention, not a public
API or permission bypass. Synthetic sensitive canaries and actual private Note
content were verified absent from stored Loki/Tempo data.

Application-to-Collector ingest requires a trusted client certificate in addition
to network labels. Grafana requires its operator login. The metadata observer
uses five namespace-scoped RoleBindings to a read-only role; it cannot read Secrets,
patch Deployments or inspect kube-system Pods. It uses Kubernetes metadata and
Connect GET status only, with no database reads, connector writes or domain writes.
Validation Pods alone have additional explicit network labels for the acceptance
checks; normal application users do not receive these capabilities.

## Alerts and recovery

| Rule | Condition | Hold |
| --- | --- | --- |
| BrainServiceUnavailable | an expected application has no Ready replica | 30s |
| BrainCdcTaskUnavailable | an expected connector or task is not RUNNING | 60s |
| BrainTelemetryMissing | an expected Java service has no JVM metrics | 2m |
| BrainObserverUnavailable | a scrape/metadata collection target fails | 60s |
| BrainKafkaLagHigh | consumer-group lag above100 | 2m |
| BrainHostMemoryLow | Linux host available RAM below10% | 2m |
| BrainProjectionRetries | repeated graph/network projection failures | 2m |

Alertmanager receives these alerts and Grafana exposes them. `operator-console`
has no email, Slack, webhook or other external delivery configured. Nothing sends
messages outside this local environment. Thresholds are an initial local baseline,
not an agreed production SLO or paging commitment.

### Service unavailable

Inspect the exact Deployment/Pod status and recent changes in Headlamp/kubectl.
Check Ready replicas, container restarts, resource limits, dependency readiness
and host memory before restarting anything. Restore the intended replica count
or fix the unhealthy release. The verified integration-service outage caused a
Gateway502, a firing Prometheus alert and Alertmanager receipt; restoring the
original replica cleared both Prometheus and Alertmanager. Do not delete PVCs.

### CDC unavailable

Read Connect connector/task status for the affected owner, broker/Registry/DB
readiness and bounded lag. Fix the underlying dependency and recover the existing
connector/task through its established procedure. Do not reset offsets, recreate
replication slots, delete topics or manually publish domain events to silence an
alert. Existing Outbox/Inbox idempotency and replay rules still apply.

For telemetry-only failures, check Collector readiness/mTLS validity, queue/disk
pressure and service export settings. Preserve the canonical applications and
their data. Certificates issued by `Provision-Observability.py` last at most60days;
rerun it for renewal and restart the affected Collector/Grafana/application Pods
sequentially so each JVM rereads its mounted client certificate. It reuses the
existing project CA and existing Grafana password. Do not rotate the root CA as
an incidental monitoring repair.

## Deploy and validate

Use the existing Python/cryptography and Helm installation. Provision the namespace
through the normal platform setup, then:

```powershell
python scripts/Install-LocalObservability.py --infrastructure-only --helm C:\path\to\helm.exe
python scripts/Install-LocalObservability.py --helm C:\path\to\helm.exe
python scripts/Run-ObservabilityValidation.py --mode acceptance
python scripts/Run-ObservabilityBoundaryValidation.py
```

The installer rolls the13 releases one at a time and records source/Deployment
UIDs in `.local/observability-installation.json`. Use the explicit source context.
`Verify-ObservabilityFaults.py` temporarily scales integration-service and Collector
to zero, restoring original replicas in `finally`; run only during an authorized
local maintenance window. Its completed evidence is retained, so an operator does
not need to repeat the outage just to open Grafana.

The backup inventory now includes `brain-observability`, its six PVCs, credentials,
five RoleBindings/ClusterRole and pinned init-container images:13 PVCs in total.
This was checked read-only. The earlier7-PVC backup and successful isolated restore
remain a valid earlier point in time; no additional13-PVC cold backup or restore
drill was performed in this milestone. Follow the [backup runbook](local-backup-recovery.md)
for a new backup. No off-host copy or automatic schedule was added.

See [validation evidence](observability-validation.txt), [dependency metadata](../infra/dependencies/observability.json)
and [retained upstream licenses](../infra/dependencies/observability). Broader load,
long-running memory/disk tests, multi-host failure/HA, production SLOs and external
notification delivery remain production-readiness work.
