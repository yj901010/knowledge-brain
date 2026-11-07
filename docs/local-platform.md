# Local platform bootstrap

Status: local Kubernetes nodes are Ready and namespaces have been applied (2026-10-06). The full platform is not deployed yet.

The existing MSA, database ownership, Kafka/outbox, Kubernetes/GitOps and observability decisions remain authoritative. The shared chart describes deployment mechanics for one application; each of the 13 services gets a separate Helm release and image. No business classes or database credentials are shared through this chart.

## Tools and cluster

Pinned tooling: k3d 5.9.0, K3s v1.35.5-k3s1, Helm 3.19.0. k3d and Helm downloads were checked against upstream SHA256 checksums. k3d uses MIT; K3s and Helm use Apache-2.0. Commercial use and redistribution are permitted subject to their conditions; preserve copyright/license and applicable NOTICE files when redistributing tooling. Tool binaries are outside the repository.

The local cluster uses one server and one agent. Kubernetes API port 6550 and ingress port 8088 bind to loopback. The script writes credentials to ignored `.local/kubeconfig.yaml` without modifying the user's global context. Existing clusters are reused and never deleted on a retry. Node containers/local storage do not provide production HA or disaster recovery.

```powershell
./scripts/Verify-Platform.ps1 -Helm C:\path\to\helm.exe
./scripts/Start-LocalCluster.ps1 -K3d C:\path\to\k3d.exe
kubectl --kubeconfig ./.local/kubeconfig.yaml --context k3d-knowledge-brain get nodes
```

Docker Desktop must provide an accessible Linux engine with cgroup v2. On 2026-10-06 the user updated the Docker/WSL environment from cgroup v1 to v2, restored the missing k3d loadbalancer configuration and restarted the nodes; both nodes became Ready. Codex can reach the Kubernetes API with network permission while direct Docker named-pipe access remains restricted. API host and startup kubeconfig use 127.0.0.1 consistently. The startup script rejects cgroup v1 before creating a cluster.

## Application deployment

The chart requires an explicit image repository/tag. Example rendering uses validation-only images and does not deploy workloads. Real images still need to be built from each Java 25 service artifact, scanned and published/imported.

Base chart values and local overrides use the same Deployment, Service, non-root security settings and probes. Local changes only reduce replicas and resource sizes. Memory limits are initial budgets; measure actual usage rather than interpreting them as a verified 32 GB capacity plan. Readiness currently confirms application startup, not future database/event dependencies.

## Remaining platform work

| Namespace | Components to provision |
| --- | --- |
| brain-data | PostgreSQL with per-service database users; Valkey; OpenSearch; JanusGraph and Cassandra; ClickHouse; MinIO; Keycloak |
| brain-events | Kafka; Debezium/Kafka Connect; Apicurio Registry |
| brain-observability | OpenTelemetry Collector; Prometheus; Loki; Tempo; Grafana |
| argocd | Argo CD and GitOps applications |
| brain-apps | 13 independently built service images/releases |

Namespaces are an operational grouping, not authorization enforcement by themselves. Service database ownership and credentials, space authorization, network policies, secrets management and ingress exposure must be implemented in their respective provisioning steps.

Before introducing each dependency: record an actual supported version and license, write local/production values, measure resources, and validate connectivity/readiness. The full prescribed stack remains required; this foundation does not replace any component with an in-memory alternative. Argo CD source registration requires the actual repository URL; no URL is invented here.

Upstream references:
- https://k3d.io/v5.9.0/usage/configfile/
- https://github.com/k3d-io/k3d/releases/tag/v5.9.0
- https://github.com/helm/helm/releases/tag/v3.19.0

PostgreSQL deployment and database-isolation checks: [postgres-bootstrap.md](postgres-bootstrap.md).

Kafka deployment configuration and validation status: [kafka-bootstrap.md](kafka-bootstrap.md).

Apicurio Registry KafkaSQL and authenticated API configuration: [registry-bootstrap.md](registry-bootstrap.md).

Runtime update 2026-10-06: PostgreSQL, Keycloak and Registry are deployed and Ready. DB isolation, Registry authorization and restart persistence checks passed; see [auth-runtime-validation.txt](auth-runtime-validation.txt) and [keycloak-bootstrap.md](keycloak-bootstrap.md).

CDC runtime update 2026-10-06: distributed Debezium/Kafka Connect and the Identity Outbox connector are deployed locally. Transport verification and recovery evidence: [connect-bootstrap.md](connect-bootstrap.md). State-changing consumer idempotency remains part of the next domain flow.

## Private search storage added (2026-10-07)

OpenSearch is now installed in brain-data with private mTLS, scoped reader/projector
certificates and a persistent single-node StatefulSet. Its 512 MiB heap, 1 GiB
memory request and 1536 MiB limit are local configuration. This does not complete
the remaining platform stack or search feature. Brain Query Kafka consumption and
canonical snapshot rebuild and fresh Space-authorized guest search routing are
implemented. Cassandra/JanusGraph storage, live dual-topic projection and canonical rebuild are implemented;
the authorized Graph API and Next.js Graph View UI are deployed. See [opensearch.md](opensearch.md)
for deployment, security, measured light-load memory and validation evidence.

Private Cassandra and Cassandra-backed JanusGraph adapter: [note-graph.md](note-graph.md).

Graph View browser access, bounds and validation: [note-graph-api.md](note-graph-api.md).

Private Knowledge Concept API and Content-owned Note references with scoped CDC: [note-concepts.md](note-concepts.md).

Registered Keycloak login, guest Space claiming and multiple private Space selection: [registered-login.md](registered-login.md).

Public Concept Contribution -> Review -> Merge: [public-contributions.md](public-contributions.md).

Private ClickHouse HTTPS storage and independently deployed Activity Learning timeline: [learning-activity-timeline.md](learning-activity-timeline.md). Central observability and other planned platform services remain incomplete.
