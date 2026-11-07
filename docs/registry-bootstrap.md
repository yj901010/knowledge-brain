# Apicurio Registry deployment foundation

Prepared 2026-10-06. Registry stores event schemas, not business domain data. It uses KafkaSQL in both base and local environments: Kafka journal and snapshot topics are durable storage, while the internal H2 database is a replayable local view. This is the official KafkaSQL design, not replacement of Kafka or PostgreSQL domain storage. Domain changes still require service-owned PostgreSQL transactions and outbox CDC.

## Dependency review

Apicurio Registry 3.3.3 is pinned. Its Apache-2.0 license permits commercial use and redistribution subject to license/NOTICE and modification obligations. No Registry runtime libraries are added to the Java services. Its official container uses its own OpenJDK 21 runtime; the application's Java 25 baseline is unchanged. Container OS packages and transitive dependencies still need enumeration before public redistribution.

- https://github.com/Apicurio/apicurio-registry/releases/tag/3.3.3
- https://github.com/Apicurio/apicurio-registry/blob/3.3.3/LICENSE
- https://github.com/Apicurio/apicurio-registry/blob/3.3.3/app/src/main/java/io/apicurio/registry/storage/impl/kafkasql/KafkaSqlConfiguration.java
- https://github.com/Apicurio/apicurio-registry/blob/3.3.3/app/src/main/resources/application.properties
- https://www.apicur.io/registry/docs/apicurio-registry/3.3.x/getting-started/assembly-registry-high-availability.html

## Storage and identities

Three explicitly managed KafkaTopic resources have one partition each: registry-journal, registry-snapshots and registry-events. Infinite time/byte retention and delete cleanup policy preserve upstream KafkaSQL requirements. Events require one partition for ordering. Base replication is three/minimum ISR two; local one/one. Avoid accidental topic deletion: Helm uninstall deletes custom resources and may cause the Topic Operator to delete their Kafka topics. Back up/export Registry and Kafka data before removal. Infinite retention requires disk monitoring and a separately validated snapshot/retention plan.

The brain-registry KafkaUser has mTLS credentials, access only to those topics and its brain-registry- consumer groups, plus Kafka's cluster-level IdempotentWrite operation. Topic creation is disabled in Registry; Topic Operator owns topic configuration. Each replica uses its own generated consumer group to rebuild the full local view on startup. Do not set a shared group.id. PKCS12 stores and password references use Strimzi's generated Secrets. HTTPS hostname verification is explicitly enabled because upstream KafkaSQL defaults disable it. Rotate certificates/passwords with a coordinated pod rollout; projected files update but Secret environment variables require restart.

Registry API uses Keycloak OIDC bearer tokens and sr-admin/sr-developer/sr-readonly roles. Anonymous and general authenticated read access are disabled. No ingress, UI deployment or public listener is supplied. The issuer URL is required and has no invented default; Keycloak realm/client/roles must exist before deployment. Set audience/role claim mapping according to that actual realm and test it before connecting clients. OIDC clientId is registry-api; service clients need separately provisioned credentials. Health checks use management port 9000; the ClusterIP Service exposes only API port 8080.

## Deploy and verify

Run from repository root with .local/kubeconfig.yaml and context k3d-knowledge-brain. Kafka/Strimzi must be Ready and KafkaTopic/KafkaUser CRDs established. Create a separate registry-auth-values.yaml containing oidc.issuerUrl from the actual Keycloak issuer. Do not put tokens or private keys in it.

```powershell
$env:KUBECONFIG = Join-Path (Get-Location) '.local/kubeconfig.yaml'
helm template brain-registry infra/charts/brain-registry -n brain-events -f infra/environments/local/registry-values.yaml -f registry-auth-values.yaml | kubectl --context k3d-knowledge-brain apply --dry-run=server -f -
helm upgrade --install brain-registry infra/charts/brain-registry -n brain-events -f infra/environments/local/registry-values.yaml -f registry-auth-values.yaml --kube-context k3d-knowledge-brain --wait --timeout 10m
kubectl --context k3d-knowledge-brain -n brain-events wait kafkatopic/registry-journal kafkatopic/registry-snapshots kafkatopic/registry-events kafkauser/brain-registry --for=condition=Ready --timeout=180s
kubectl --context k3d-knowledge-brain -n brain-events rollout status deployment/brain-registry --timeout=300s
```

Registry pods can wait for the User Operator to generate credentials during the initial deployment. Inspect CR conditions/operator logs if startup fails; do not delete storage as a retry. Before client integration, verify anonymous API requests are denied; sr-readonly can read but cannot write; sr-developer can register Protobuf; malformed and incompatible changes are rejected after setting VALIDITY=FULL and the chosen compatibility rule. Use a new artifact/event version for breaking changes. Record rules and fixtures with the event-contract implementation; this chart does not claim compatibility rules have been installed.

Check restart persistence by registering a disposable Protobuf artifact, restarting the Deployment and reading the same version/content hash. Test a Kafka identity without topic ACLs and a wrong CA are rejected. Test multi-replica replay and broker failure in a non-local cluster. Prometheus scraping, telemetry, recovery, image-digest pinning and measured sizing remain pending.

## Validation status

Offline base/local Helm lint and render, CRD structural checks and security/storage invariants are recorded in registry-validation.txt. Missing issuer and invalid ISR configuration are rejected. The validation issuer is a test-only value and is not saved in environment defaults. Docker is inaccessible, so no Kubernetes admission, image pull, startup, OIDC, TLS/ACL, schema registration or restart-persistence test has run. This is deployment configuration preparation; a running Registry is not complete.

Runtime update 2026-10-06: Keycloak has been provisioned. Local values now contain the actual internal TLS issuer and public trust Secret. Registry Ready, role access tests, Protobuf registration/read and restart persistence passed; see auth-runtime-validation.txt. For Quarkus 3.33.3.1 bundled in this image the supported named TLS property is quarkus.oidc.tls.tls-configuration-name, confirmed from versioned upstream source. The older configuration-only/missing-Keycloak statements above are historical; base values still require an explicit production issuer. Registry API remains internal HTTP; end-to-end API TLS/public ingress is pending.
