# Kafka deployment foundation

Prepared on 2026-10-06. This configuration preserves the Kafka event backbone required by AGENTS.md and PROJECT.md. It does not publish business changes directly from service code: transactional outbox and Debezium/Kafka Connect remain mandatory.

## Versions and license

Strimzi 1.2.0 manages Kafka 4.3.1 using the kafka.strimzi.io/v1 API. Its supported Kubernetes range includes the local k3s 1.35 environment. Strimzi and Apache Kafka use Apache-2.0; commercial use and redistribution require applicable license/NOTICE preservation and marking modifications. This review does not clear all container operating-system packages.

- https://strimzi.io/downloads/
- https://strimzi.io/docs/operators/1.2.0/deploying
- https://github.com/strimzi/strimzi-kafka-operator/blob/1.2.0/LICENSE
- https://github.com/apache/kafka/blob/4.3.1/LICENSE

## Layout and access

One controller-only pool and one broker-only pool exist in both environments. Base values use three of each, replication factor three and minimum ISR two. Local values use one of each, replication factor one and minimum ISR one. Local has no broker or quorum failover. PVCs are retained on resource removal.

Only an internal TLS listener on 9093 is configured, with client certificate authentication and simple ACL authorization. No anonymous/plaintext listener or public NodePort is exposed. User Operator creates per-client KafkaUser certificates; Topic Operator manages explicit KafkaTopic resources. No application identity, wildcard ACL, or domain topic is fabricated at this stage. Establish producer-owned topic contracts, consumer group permissions and KafkaUser ACLs before connecting applications. Use acks=all and idempotent producers, but consumers still require stable eventId deduplication for state changes.

## Deployment after Docker and k3d are available

Use the generated local kubeconfig and explicit context. Install the pinned official operator first; Helm installs its CRDs on initial installation, but future CRD upgrades require the upstream upgrade procedure.

```powershell
$env:KUBECONFIG = Join-Path (Get-Location) '.local/kubeconfig.yaml'
helm repo add strimzi https://strimzi.io/charts/
helm repo update strimzi
helm upgrade --install strimzi strimzi/strimzi-kafka-operator --version 1.2.0 --namespace brain-events --kube-context k3d-knowledge-brain --wait --timeout 5m
kubectl --context k3d-knowledge-brain wait --for=condition=Established crd/kafkas.kafka.strimzi.io crd/kafkanodepools.kafka.strimzi.io --timeout=120s
helm template brain-kafka infra/charts/brain-kafka -n brain-events -f infra/environments/local/kafka-values.yaml | kubectl --context k3d-knowledge-brain apply --dry-run=server -f -
helm upgrade --install brain-kafka infra/charts/brain-kafka -n brain-events -f infra/environments/local/kafka-values.yaml --kube-context k3d-knowledge-brain
kubectl --context k3d-knowledge-brain -n brain-events wait kafka/brain-kafka --for=condition=Ready --timeout=300s
```

`helm --wait` on a custom-resource-only chart is not sufficient evidence of Kafka readiness; wait for the Kafka Ready condition explicitly. Do not delete retained PVCs to repair a failed startup. Diagnose operator logs and resource conditions first. Base values are a scaling baseline, not a production certification: multi-zone placement, backup/recovery, certificate rotation, Prometheus scraping, resource measurement and failure tests remain pending.

## Validation and next work

Offline Helm lint/render and checks against the pinned upstream CRD structure are recorded in kafka-validation.txt. Kubernetes admission, image pulls, PVC binding, TLS/ACL positive and negative tests, producer/consumer delivery and failure behavior have not been validated because Docker is unavailable. This step is configuration preparation, not a completed running event platform.

Apicurio Registry, its durable storage and authenticated Kafka connection are the next event-platform step. Debezium/Kafka Connect, service-specific outbox migrations, Protobuf contracts, schema compatibility rules, telemetry and end-to-end idempotency tests remain outstanding. No business service is connected to Kafka yet.

Runtime update 2026-10-06: local Strimzi and Kafka deployed; Kafka Ready and PVC Bound checks passed. See kafka-runtime-validation.txt. Earlier Docker/runtime blockers above are historical; TLS/ACL message delivery and failover tests remain pending.
