# Build foundation

Date: 2026-10-05
Updated: 2026-10-06
Status: Java + Spring Boot selected by the user; Java 25 LTS selected by explicit user decision on 2026-10-06. Maven reactor is an initial implementation choice.

One repository contains 13 independent application modules. The root POM centralizes build configuration only; modules do not depend on each other's Java domain classes. Each service remains separately executable and owns future persistence. This preserves AGENTS.md sections 2, 4, 5 and PROJECT.md sections 11-12.

Spring Boot 4.1.1 is pinned to the stable version verified in official documentation and Maven Central. Maven 3.10.0 is pinned via wrapper. Compiler release and initial runtime baseline are Java 25 LTS. Spring Boot virtual threads are enabled. This is a runtime choice within the existing architecture, not a service-boundary change.

No domain API, database mutation, event publication or public knowledge dataset is added. REST/OpenAPI, Kafka/Protobuf/Apicurio, outbox, Kubernetes/GitOps and OpenTelemetry obligations remain open for their implementation phases. Probe readiness currently means application startup only, not readiness of future dependencies.

The gateway module is an executable placeholder, not a functioning router. Brain Query and Activity have no canonical domain writes. No architecture decision in AGENTS.md or PROJECT.md is replaced.
