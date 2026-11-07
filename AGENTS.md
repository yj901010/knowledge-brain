# Knowledge/Brain Platform - Codex Instructions

## 1. Project

This repository contains the Knowledge/Brain Platform.

The product combines:

- Shared Knowledge Brain
- Personal Brain
- Group Brain
- Learning and roadmap management
- Planning and scheduling
- GitHub activity integration
- Knowledge contribution and verification

The first public knowledge domains are:

### Medicine
- Liver cancer
- Breast cancer
- Lung cancer
- Colorectal cancer
- Chronic myeloid leukemia (CML)

### Development
- Backend development
- Computer Science

Read `PROJECT.md` before making architectural or cross-service changes.

---

## 2. Architecture Principle

V1 is NOT a reduced MVP architecture.

The project must use the intended MSA architecture from the beginning.

Local development may reduce:

- replica count
- CPU requests
- memory limits
- storage size

Do NOT simplify:

- service boundaries
- database ownership
- asynchronous event architecture
- deployment architecture
- consistency model

Local and production environments must use the same logical architecture.

---

## 3. AI Policy

V1 contains no AI functionality.

Do not introduce:

- LLM
- RAG
- embedding
- vector database
- AI recommendation
- AI parser
- AI agent

The architecture must allow a future Intelligence Platform to consume existing APIs and domain events without restructuring core services.

---

## 4. Microservice Boundaries

The planned services are:

- gateway-service
- identity-service
- space-service
- knowledge-service
- content-service
- learning-service
- planning-service
- collaboration-service
- activity-service
- media-service
- integration-service
- notification-service
- brain-query-service

Do not merge services merely to make local development easier.

A service owns and mutates only its own domain data.

---

## 5. Data Ownership

Each service owns its own database/schema contract.

Rules:

- Cross-service database queries are forbidden.
- Cross-service joins are forbidden.
- Cross-service foreign keys are forbidden.
- One service must never directly modify another service's tables.
- References to another domain use immutable IDs.
- PostgreSQL is the source of truth for transactional domain data.

Complex multi-domain reads must eventually use query/read projections rather than distributed joins.

---

## 6. IDs

Persistent domain identifiers use UUIDv7.

Examples:

- user_id
- space_id
- concept_id
- claim_id
- source_id
- roadmap_id
- media_id
- event_id

Each service generates identifiers for aggregates it owns.

---

## 7. Space Model

`space_id` is the primary tenancy boundary.

Supported space types:

- PUBLIC
- PERSONAL
- GROUP
- ORGANIZATION

Personal data is PRIVATE BY DEFAULT.

Personal content must never become public automatically.

Publishing or contributing personal knowledge requires an explicit user action.

---

## 8. Guest Users

The service must support anonymous use.

Anonymous users receive:

- persistent guest identity
- personal guest space
- server-side stored data
- long-lived secure browser identity

Do not create normal Keycloak users for every guest.

Registered authentication uses Keycloak.

Guest identity and registered identity must be represented behind a common application principal abstraction.

When a guest later registers or logs in, their existing Personal Space must be claimable without recreating all domain data.

Guest restrictions may apply to:

- public contributions
- group creation
- GitHub integration
- file storage quota

---

## 9. Knowledge Model

Knowledge is domain-independent.

Do not create separate Medicine and Development knowledge services.

Core knowledge entities include:

- Concept
- Alias
- Claim
- Claim Qualifier
- Source
- Revision
- Verification
- Contribution
- Review
- External Mapping

Medicine and Development are domain definitions built on the same Knowledge Core.

Relationships are modeled primarily as claims:

Subject -> Predicate -> Object

The object may be:

- another Concept
- String
- Number
- Date
- Boolean
- Enum/typed value

Knowledge must support revision history.

Prefer lifecycle states such as:

- ACTIVE
- DEPRECATED
- RETIRED
- MERGED

over destructive deletion.

---

## 10. Public Contribution Model

Public knowledge changes follow:

Contribution
-> Review
-> Merge

Do not allow arbitrary direct modification of verified public knowledge.

Medical claim verification uses separate verification and external-mapping dimensions.

Example verification progression:

- DRAFT
- SOURCED
- COMMUNITY_REVIEWED
- EXPERT_VERIFIED

External terminology mapping state is independent:

- NONE
- CANDIDATE
- CONFIRMED
- OFFICIAL

---

## 11. Communication

External and synchronous internal APIs:

- REST
- JSON
- OpenAPI

Do not introduce gRPC without measured justification.

Asynchronous communication:

- Kafka
- Protobuf event contracts
- Schema Registry

Domain changes that need propagation must use domain events rather than direct cross-service database access.

---

## 12. Transaction Model

Transactions are local to one service.

Do not implement distributed database transactions across services.

Use:

- local ACID transaction
- eventual consistency
- Saga only when a real multi-service workflow requires compensation

---

## 13. Event Reliability

Domain-event publication uses the Transactional Outbox pattern.

Target flow:

Business transaction
-> Outbox
-> Debezium / Kafka Connect
-> Kafka

Consumers must assume at-least-once delivery.

Every consumer handling state changes must be idempotent.

Events must include a stable `eventId`.

Do not assume exactly-once delivery between arbitrary services.

---

## 14. Event Envelope

Domain events should include at minimum:

- eventId
- eventType
- eventVersion
- occurredAt
- producer
- aggregateType
- aggregateId
- spaceId where applicable
- actorId where applicable
- traceId
- payload

Breaking event changes require a new event version.

---

## 15. Storage

Transactional source of truth:
- PostgreSQL

Event backbone:
- Apache Kafka

CDC:
- Debezium / Kafka Connect

Schema Registry:
- Apicurio Registry

Cache:
- Valkey

Search read model:
- OpenSearch

Graph read model:
- JanusGraph
- Cassandra
- OpenSearch where required by JanusGraph indexing

Analytics:
- ClickHouse

Binary/object storage:
- MinIO

Authentication:
- Keycloak

Graph, search, analytics, and cache stores are not canonical transactional sources of truth.

They must be rebuildable from canonical data/events where applicable.

---

## 16. Media

Do not store large image/PDF/audio binary data inside PostgreSQL.

Media flow should use:

Client
-> Media Service
-> presigned URL
-> MinIO

The Media Service owns file metadata.

The domain that uses a file owns the business relationship to that media ID.

Example:

Content Service owns Note -> Media relationship.

Media Service does not need to know Note business semantics.

---

## 17. Roadmaps

Anyone may create roadmaps.

Roadmaps may be private or public.

Verified public roadmaps use a review process.

Verification and popularity/ranking are separate.

Ranking may later consider:

- rating
- usage
- completion rate
- freshness
- verification status

Do not treat popularity as verification.

---

## 18. Runtime

Canonical deployment runtime:

- Kubernetes

Local development:

- k3d
- single physical machine
- 32 GB RAM

Local environment must preserve the same logical production architecture.

Use resource limits and replica reduction rather than replacing distributed components with unrelated alternatives.

Production may scale replicas independently.

---

## 19. Observability

All services must support OpenTelemetry.

Target stack:

- OpenTelemetry
- Prometheus
- Loki
- Tempo
- Grafana

Propagate trace identifiers across:

- HTTP
- Kafka events
- asynchronous processing

Observability is part of V1, not an afterthought.

---

## 20. Delivery

CI:
- GitHub Actions

Container registry:
- GHCR

CD:
- Argo CD

Deployment:
- Kubernetes / GitOps

Infrastructure and application configuration must distinguish local and production through environment/Helm values, not architecture forks.

---

## 21. License Direction

The service is intended to be publicly available and open source.

Before adding dependencies or datasets:

- verify their license
- verify commercial-use terms
- verify redistribution rights

Pay special attention to medical terminology and clinical datasets.

Do not copy or redistribute external medical terminology datasets merely because they are publicly viewable.

---

## 22. Architecture Changes

Do not silently change an established architecture decision.

If implementation reveals that an established decision is impractical:

1. identify the conflicting decision,
2. provide evidence,
3. explain alternatives,
4. describe migration impact,
5. request/record an architectural decision before restructuring.

Measured results such as benchmarks, load tests, failure tests, and operational complexity are preferred over speculative optimization.

---

## 23. Definition of Done

A change is not complete until applicable items are satisfied:

- implementation
- unit tests
- integration tests where appropriate
- API/event contract updates
- database migration
- observability
- error handling
- security/authorization
- documentation update
- architecture compatibility
- local Kubernetes validation

Do not consider code generation alone to be task completion.