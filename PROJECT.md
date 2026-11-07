# Knowledge/Brain Platform

## 1. Product Vision

Knowledge/Brain Platform is a system for connecting:

- shared verified knowledge,
- personal knowledge,
- personal activity and learning,
- future plans,
- and collaborative group learning

into one navigable knowledge network.

The system should eventually answer structurally useful questions such as:

- What do I know?
- What did I study?
- What am I currently learning?
- What am I missing?
- What should I study next?
- What did I do on a particular day?
- Which notes, tasks, projects, and activities relate to this concept?
- What does the community know about this concept?
- Which claims are verified and what sources support them?

V1 must provide useful functionality without AI.

---

# 2. Product Structure

The product has three user-facing knowledge layers.

## Shared Brain

Public and reusable knowledge.

Initial domains:

### Medicine
- Liver cancer
- Breast cancer
- Lung cancer
- Colorectal cancer
- Chronic myeloid leukemia (CML)

### Development
- Backend development
- Computer Science

Future domains may include:

- Finance
- Law
- Language
- Science
- Other professional knowledge

---

## Personal Brain

The default home experience.

A Personal Brain may contain:

- personal concepts
- notes
- questions
- decisions
- bookmarks
- tasks
- schedules
- goals
- study sessions
- roadmap progress
- files
- images
- later audio recordings
- GitHub activities
- learning evidence

Public concepts may be referenced from a Personal Brain without being duplicated.

---

## Group Brain

Used for:

- study groups
- courses
- learning communities
- organizations
- team learning

Groups can contain:

- shared roadmaps
- assignments
- discussions
- course content
- submissions
- progress information

Progress visibility is configurable by the group.

Course progress sharing decision (2026-10-24): default off. A Group OWNER may enable
Group-wide sharing, but each participant must explicitly publish their completion-count
snapshot to the current OWNER only. Private Study progress/history remains participant-only.
Private updates never update shared snapshots automatically; consent is invalidated by
policy off/on, membership changes or enrollment withdrawal/rejoin. See
`docs/course-progress-sharing.md`.

Submission grading decision (2026-10-24): current Group OWNER explicitly assigns
integer 0..100 points to another author's selected submitted version. Self-grading
is forbidden. Score, reason and immutable history are visible only to the author
and current OWNER. Resubmission never transfers a previous score; private learning
progress is never changed automatically. See `docs/submission-grades.md`.

Assignment rubric decision (2026-10-24, user approved): current Group OWNER may
edit optional ordered evaluation criteria only while Assignment is DRAFT. Nonempty
criteria total100 integer points; opening submissions permanently freezes them.
Current participants read shared criteria/history. Existing private0..100 manual
grades remain independent; this original step defined criteria only and never
automatically changes private learning progress.
See `docs/assignment-rubrics.md`.

Rubric grading decision (2026-10-25, user approved): OWNER chooses existing direct
0..100 grading or server-computed sum of the frozen Assignment criteria. Both use
the same private submitted-version grade and sgN CAS, forbid self grading, and
preserve original instructions. A changed calculation basis or explicit mode change
appends a grade revision even at the same total; exact unchanged calculation and
the legacy manual no-op remain unchanged. Full rubric/scores/comments are immutable
per grade version and visible only to author/current OWNER. Retraction remains
per grade version; private progress never changes automatically.
See `docs/rubric-grading.md`.

Submission attachment decision (2026-10-25, user approved): newly uploaded files
are pinned to an explicit submitted version and visible only to its author and current
Group OWNER. Personal files are never imported automatically. Media owns file metadata
and immutable object versions; Collaboration owns the submission relationship and
checks current Space authority. See `docs/submission-files.md`.

Personal Brain content remains private unless explicitly shared.

---

# 3. V1 Scope

V1 deliberately excludes AI.

V1 includes:

## Knowledge

- Concept
- Alias
- Claim
- Qualifier
- Source
- Revision
- Verification
- External Mapping
- Contribution
- Review
- Graph visualization
- Table visualization
- Search

## Personal Brain

- Notes
- Questions
- Decisions
- Bookmarks
- Personal concepts/claims
- Concept linking
- Timeline

Personal Brain implementation includes these planned capabilities:

- Markdown notes with revision history and concurrent-edit protection
- `[[Wiki Link]]` references backed by stable note IDs, including unresolved links
- Backlinks
- Space-scoped tags
- Typed note properties
- Permission-filtered Graph View
- Daily Note / Journal with Space, date and timezone rules
- Attachments through Media Service and MinIO
- Permission-filtered note search through OpenSearch projections
- Explicit Note-to-Concept links using Knowledge-owned concept IDs

These capabilities preserve the existing Content, Space, Knowledge, Media,
Activity and Brain Query ownership and event-driven read-model architecture.

## Learning

- Roadmaps
- Roadmap nodes and edges
- Study sessions
- Progress
- Evidence
- Roadmap ratings
- Roadmap ranking

## Planning

- Goals
- Tasks
- Schedules
- Reminders
- Recurrence

## Collaboration

- Group
- Study
- Course
- Assignment
- Discussion
- Submission

## Integration

- GitHub
- repositories
- push/commit activity
- pull-request activity where useful
- issue activity where useful

## Media

- image
- PDF
- generic files
- future audio support

## Platform

- authentication
- anonymous user support
- authorization
- public/private/group spaces
- notification
- activity timeline
- observability
- Kubernetes deployment
- CI/CD

---

# 4. Guest Experience

Login must not be mandatory for basic Personal Brain usage.

On first anonymous use:

1. create a guest principal,
2. create a Personal Space,
3. persist data server-side,
4. issue a secure long-lived guest browser token.

Returning from the same browser should restore the Personal Brain.

Guest users should be able to:

- browse public knowledge
- create private notes
- create tasks
- use roadmaps
- record progress
- maintain Personal Brain data

Some capabilities require registration:

- contributing to public knowledge
- expert/community review
- GitHub integration
- possibly group creation
- higher file quota

When the user registers:

Guest Personal Space
-> claim by registered user

Avoid copying/recreating every object where ownership transfer is sufficient.

---

# 5. Public Knowledge Contribution

Public knowledge follows a pull-request-like workflow.

User proposal:

Contribution
-> Review
-> Merge
-> New Revision

Contributors do not directly mutate verified canonical public knowledge.

Every meaningful knowledge change should remain auditable.

---

# 6. Medical Knowledge

The initial medical focus is oncology/hematology.

Seed diseases:

- Liver cancer
- Breast cancer
- Lung cancer
- Colorectal cancer
- CML

Initial concept types may include:

- Disease
- Medical Term
- Drug Ingredient
- Drug Product
- Drug Class
- Regimen
- Test
- Biomarker
- Procedure

The Knowledge Core must NOT be oncology-specific.

Other specialties must be addable without redesigning the core model.

Medical knowledge should support local terminology.

Examples of scopes:

- GLOBAL
- COUNTRY
- ORGANIZATION/HOSPITAL
- DEPARTMENT
- USER/CLINICIAN

Local aliases must not automatically become universal aliases.

Medical terminology sources and datasets require explicit license tracking.

---

# 7. Medical Verification

Knowledge verification and external terminology mapping are separate concepts.

## Claim Verification

Possible states:

- DRAFT
- SOURCED
- COMMUNITY_REVIEWED
- EXPERT_VERIFIED

## External Mapping

Possible states:

- NONE
- CANDIDATE
- CONFIRMED
- OFFICIAL

Potential external systems include, subject to licensing:

- SNOMED CT
- RxNorm
- ATC
- Korean medical/drug coding systems
- other appropriate terminology standards

External mappings must include provenance and version where possible.

---

# 8. Development Knowledge

Initial scope:

## Programming / Backend

- Java
- Spring
- databases
- Redis/Valkey concepts
- Kafka
- distributed systems
- architecture
- cloud
- networking

## Computer Science

- operating systems
- networks
- databases
- data structures
- algorithms
- computer architecture
- concurrency
- distributed systems

Development knowledge uses the same Concept/Claim architecture as medical knowledge.

---

# 9. Roadmaps

Roadmaps are graphs, not only ordered lists.

Possible relationships include:

- PREREQUISITE_OF
- RECOMMENDED_BEFORE
- PART_OF
- REQUIRED_FOR
- OPTIONAL_FOR
- RELATED_TO

Anyone may create a roadmap.

A roadmap may be:

- PRIVATE
- PUBLIC
- VERIFIED_PUBLIC

Verification and ranking are separate.

Possible ranking factors:

- rating
- usage count
- completion rate
- verification
- recency
- user goal/category match

V1 recommendation should be deterministic/rule-based.

AI recommendation is excluded.

---

# 10. Spaces

Every relevant object belongs to or is visible through a Space.

Types:

- PUBLIC
- PERSONAL
- GROUP
- ORGANIZATION

`space_id` is a major tenancy and future partitioning key.

Examples:

- PUBLIC_MEDICINE
- PUBLIC_DEVELOPMENT
- PERSONAL_xxx
- GROUP_xxx
- ORGANIZATION_xxx

Privacy default:

PERSONAL -> PRIVATE

Data moves from Personal to Public only through explicit user action.

---

# 11. Architecture

V1 is implemented as MSA.

Logical services:

1. gateway-service
2. identity-service
3. space-service
4. knowledge-service
5. content-service
6. learning-service
7. planning-service
8. collaboration-service
9. activity-service
10. media-service
11. integration-service
12. notification-service
13. brain-query-service

Each service owns its domain.

No service may directly access another service database.

---

# 12. Service Ownership

## Identity Service

Owns:

- User
- Profile
- application account state
- Keycloak identity mapping

Keycloak owns authentication.

---

## Space Service

Owns:

- Space
- Membership
- Role
- Permission
- Invitation
- Visibility

Answers:

"What may this principal do in this space?"

---

## Knowledge Service

Owns:

- Concept
- Concept Type
- Alias
- Claim
- Claim Qualifier
- Source
- Revision
- Verification
- Contribution
- Review
- External Mapping
- Domain Schema

This is the canonical knowledge authority.

---

## Content Service

Owns:

- Note
- Document
- Question
- Decision
- Bookmark
- Comment
- Content-Concept Link
- Content-Media Link

Knowledge itself is not owned here.

---

## Learning Service

Owns:

- Roadmap
- Roadmap Version
- Roadmap Node
- Roadmap Edge
- Roadmap Review
- Roadmap Rating
- Study Session
- Progress
- Evidence

---

## Planning Service

Owns:

- Goal
- Task
- Schedule
- Reminder
- Recurrence

---

## Collaboration Service

Owns:

- Study
- Course
- Group configuration
- Assignment
- Discussion
- Submission

Space membership remains owned by Space Service.

---

## Activity Service

Owns activity/timeline projections.

It must not become the canonical owner of facts owned by another service.

Example:

StudySessionCompleted is owned by Learning.

Activity only projects it onto a timeline.

---

## Media Service

Owns:

- Media metadata
- Upload lifecycle
- MIME metadata
- checksum
- variants
- thumbnail metadata

Binary files live in MinIO.

Business relationships to media are owned by the consuming domain.

---

## Integration Service

Owns:

- GitHub connection
- OAuth credential/token handling
- Webhook subscriptions
- repository mappings
- synchronization cursor/state
- external resource mapping

External events are translated into platform domain/integration events.

---

## Notification Service

Owns:

- Notification
- Notification preference
- Delivery
- Retry
- Delivery history

---

## Brain Query Service

Owns no canonical domain data.

Provides read-optimized views such as:

- Personal Brain
- Concept page
- Group dashboard
- Feed
- graph exploration
- combined views

---

# 13. Communication

## Synchronous

REST + JSON + OpenAPI

Use when the caller requires an immediate authoritative answer.

Avoid long chains of synchronous service calls.

## Asynchronous

Kafka + Protobuf + Schema Registry

Use for facts that other services may react to asynchronously.

Examples:

- ConceptCreated
- ConceptUpdated
- ClaimVerified
- NoteCreated
- StudySessionCompleted
- TaskCompleted
- MediaUploaded
- GitHubPushReceived

---

# 14. Event Reliability

Transactional Outbox is mandatory where domain state and event publication must remain consistent.

Target:

PostgreSQL transaction
-> business mutation
-> outbox record
-> commit
-> Debezium
-> Kafka

Consumer semantics:

at-least-once delivery

Therefore consumers must implement:

- idempotency
- duplicate detection where state change matters
- stable event IDs

---

# 15. CQRS / Read Models

Canonical writes happen in owning services.

Complex read views may use projections.

Planned read models:

- Search Projection
- Graph Projection
- Brain Projection
- Activity Analytics

Examples:

Knowledge events
-> Search Projector
-> OpenSearch

Knowledge events
-> Graph Projector
-> JanusGraph

multiple domain events
-> Brain Projector
-> Brain Query Service

activity events
-> ClickHouse

Read models must be rebuildable.

---

# 16. Storage Stack

## PostgreSQL

Canonical transactional data.

Database ownership is per service.

Local development may place multiple logical service databases on the same PostgreSQL instance.

This does not change logical ownership.

## Kafka

Event backbone.

## Debezium / Kafka Connect

Transactional Outbox CDC.

## Apicurio Registry

Event contract/schema registry.

## Valkey

Cache, rate limiting, and short-lived coordination only.

Never canonical business data.

## OpenSearch

Search projection.

Not source of truth.

## JanusGraph

Graph projection.

## Cassandra

Distributed graph backing storage for JanusGraph.

## ClickHouse

High-volume analytical/activity queries.

## MinIO

Object storage for:

- image
- PDF
- audio
- attachments

---

# 17. Authentication and Authorization

Authentication:

Keycloak / OIDC / OAuth2

Authorization:

Space Service

Initial authorization model:

RBAC

Example roles:

- OWNER
- ADMIN
- EDITOR
- CONTRIBUTOR
- REVIEWER
- MEMBER
- VIEWER

Possible future extension:

ABAC/policy-based authorization.

---

# 18. Frontend

Web first.

Recommended frontend:

- Next.js
- TypeScript

Mobile application is future scope.

Web APIs must therefore avoid assumptions that make mobile support difficult later.

---

# 19. Runtime and Local Environment

Canonical runtime:

Kubernetes

Local development machine:

- single PC
- 32 GB RAM

Local Kubernetes:

k3d

All production architecture components remain logically present locally.

Local differences are configuration only:

- replica count
- heap
- CPU
- RAM
- storage volume size

Do not replace architecture components simply because local traffic is small.

---

# 20. Observability

Required from V1:

- OpenTelemetry
- Prometheus
- Loki
- Tempo
- Grafana

Trace context should propagate through:

HTTP
-> service
-> outbox/event
-> Kafka
-> consumer

Metrics should allow future architectural decisions to be justified by measurements.

Examples:

- service latency
- p95/p99
- DB pool saturation
- Kafka consumer lag
- search latency
- graph traversal latency
- memory
- CPU
- GC
- event processing failure
- retry rate

---

# 21. CI/CD

Source:
GitHub

CI:
GitHub Actions

Container Registry:
GHCR

CD:
Argo CD

Deployment:
GitOps + Kubernetes

Desired pipeline:

commit
-> tests
-> integration tests
-> contract validation
-> build
-> container image
-> security scan
-> registry
-> deployment configuration
-> Argo CD
-> Kubernetes

---

# 22. Licensing

The project is intended to be open source and publicly usable.

Project-code license and remote delivery decision (2026-11-07, user approved):
original project software uses Apache-2.0, with LICENSE/NOTICE and existing
third-party attributions retained. Public source delivery is authorized to
`yj901010/knowledge-brain` on `main`, including the independently unreviewed21
authoring drafts. This does not publish those drafts as canonical Knowledge or
relicense their cited sources. Actual GitHub Actions and thirteen initially
private GHCR application packages are authorized within free allowances only.
Extra paid usage, package-public visibility and adoption of existing applications
into GitOps remain separate decisions. See `docs/project-license.md` and
`docs/remote-delivery-execution.md` for scope and execution evidence boundaries.

Infrastructure dependencies must have their licenses reviewed.

Special attention is required for:

- MinIO / AGPL
- Grafana ecosystem / AGPL
- medical terminology datasets
- clinical guidelines
- external drug/medical datasets

Open-source infrastructure licensing and redistribution rights for the actual medical data are separate questions.

A public web page does not imply that its underlying dataset may be copied and redistributed.

---

# 23. Scaling Philosophy

Do not pre-optimize service code for imagined traffic.

However, do preserve the final architectural boundaries from V1.

Scale by:

- adding service replicas
- separating physical DB clusters
- increasing Kafka brokers
- scaling OpenSearch
- scaling Cassandra/JanusGraph
- expanding ClickHouse
- expanding object storage

The same logical architecture should survive these changes.

---

# 24. High Availability Target

Future production target:

- stateless service replicas
- multiple availability zones
- database replication/failover
- replicated Kafka
- replicated search/graph/analytics stores
- external backups
- observability
- disaster recovery

A single local machine is a development environment, not site-level HA.

---

# 25. Future Intelligence Platform

Not part of V1.

Future AI features must integrate as additional consumers/clients of existing platform data and events.

Potential future areas:

- terminology interpretation
- note parsing
- automatic concept suggestions
- roadmap recommendation
- knowledge gap detection
- personal assistant
- RAG/search assistance

The core platform must remain useful if every AI component is unavailable.


# Group submission notification decision — 2026-10-25

Registered participants may explicitly enable per-Group in-app notifications for changes to their own submission feedback, resubmission requests and recorded/corrected grades. Default off; no retroactive delivery. Inbox stores only change kind and immutable source identifiers, never answer/score/opinion/file contents. Only the recipient reads it; all APIs recheck current Group membership through Space. Membership-version changes require fresh consent and hide the prior epoch inbox. Notification owns preferences/inbox/audit/Outbox; Collaboration remains source owner and supplies a bounded content-free recovery export. Existing personal reminder settings remain independent. See `docs/group-submission-notifications.md`.


Group notification follow-up (2026-10-25): the existing opt-in own-submission review scope includes feedback/grade version retraction and restoration. The inbox remains content-free and author-only; exact historical targets resolve through the original Collaboration API with current authority. A persistent rollout cutoff prevents backfilling newly supported historical annotations. No scoring, progress or privacy-policy changes. See `docs/review-lifecycle-notifications.md`.


OWNER grading queue (2026-10-27): current Group OWNER can filter other authors' submitted work by missing, outdated, current-version retracted or current grade metadata. Exact latest grade-version annotations govern the queue; zero/direct/rubric grades share the same meaning. Scores/reasons remain in original submission detail, self-grading stays forbidden, and no learning progress changes automatically. Separate read-only endpoint preserves the original submission review queue. See `docs/submission-grading-queue.md`.


Study own-submission overview (2026-10-27): current registered Group participants, including VIEWER, can list only their own submitted work across one Study and open the original freshly authorized assignment. Current grade metadata uses the existing exact-version semantics; no scores or answer contents are copied into the overview. This does not enumerate peers, non-submitters or mutate grades/private progress. See `docs/study-my-submissions.md`.


Study own resubmission-request overview (2026-10-27): current registered participants, including VIEWER, may list only existing requests on their own submitted work across one Study. Fresh original source reads retain existing request/submit permissions and exact answer-version resolution semantics. Default pending filter, optional resolved/cancelled/unknown states; no reason, answer, score, peer roster, notification or private progress mutation. Separate read-only endpoint preserves current submission and OWNER queues. See `docs/study-my-resubmissions.md`.


Study own-feedback overview (2026-10-28): current registered participants, including VIEWER, list only existing feedback on their own submissions in one Study. Current/older-answer status and exact latest feedback-version annotations remain independent; the retracted filter includes older-answer latest feedback. Unknown metadata never counts as current. No comment, reason, score, peer roster, unread tracking, notification or private progress mutation. Original authorized submission detail remains the source. Separate read-only endpoint preserves existing queues. See `docs/study-my-feedback.md`.


Study own-assignment overview (2026-10-28): current registered participants including VIEWER can inspect shared assignments with only their own submission presence. Default OPEN_NOT_SUBMITTED requires an OPEN Assignment, a nonarchived Study and no own submission. ALL, OPEN, SUBMITTED and NOT_SUBMITTED remain point-in-time filters, never submission permission, individual assignment obligation or private completion. Archived/DRAFT definitions remain visible under existing shared-read policy; original detail freshly checks writes. No peer roster, answer, score or personal progress sharing. Separate read-only API preserves existing queues. See `docs/study-my-assignments.md`.


Course own-assignment overview (2026-10-28): current registered Group participants can inspect current curriculum Studies and only own assignment submission presence, independently of enrollment/private progress. OPEN filters exclude archived Course/Study; ALL retains existing shared read policy. Rows use current Study/Assignment labels without rewriting pinned Course snapshots. Explicit source navigation rechecks current curriculum, then original Study/Assignment authority. No peer roster, enrollment, progress, deadline or notification mutation. See `docs/course-my-assignments.md`.


Course own-submission grading overview (2026-10-28): current registered Group participants may list only own submitted work within current canonical Course curriculum, independently of enrollment/private progress. Exact latest grade-version metadata and historical retraction isolation reuse existing Study semantics; score0 and rubric0 count as grades. Scores/reasons/answers remain in original authorized source. Archived parents retain reads. Fresh curriculum/source navigation, no roster, enrollment, notification or private progress mutation. See `docs/course-my-submissions.md`.


Course own resubmission-request overview (2026-10-28): current registered Group participants including VIEWER may list only existing requests on their own submissions in current canonical Course curriculum, independently of enrollment/private progress. Pending default, resolved/cancelled/unknown filters retain exact answer-version semantics. Resolution means newer answer arrival, never grading/completion. Reasons/answers remain in fresh original source; archived parents retain reads. No peer roster, request/notification/progress mutation. Existing Study/Course overviews and source writers are unchanged. See `docs/course-my-resubmissions.md`.


Course own-feedback overview (2026-10-30): current registered Group participants including VIEWER may list only existing feedback on own submissions within current canonical Course curriculum, independently of enrollment/private progress. ALL default; current/older-answer and exact latest feedback-version withdrawal/restoration retain existing Study meanings, including older-answer withdrawal in RETRACTED. Unknown metadata never counts as current. Current labels preserve pinned Course history. Private comments/reasons remain in fresh original source; archived parents retain reads. No peer roster, unread tracking, source/notification/progress mutation. Existing endpoints/writers unchanged. See `docs/course-my-feedback.md`.


Course OWNER grading queue (2026-10-30): freshly authorized registered Group OWNER may list other authors' submitted grading metadata across current canonical Course curriculum. Existing seven filters/default NEEDS_GRADING and exact latest grade-version annotations apply; direct/rubric0 count as grades, own submissions excluded. Source selection rechecks current curriculum and exact Study/Assignment/Submission authority, opening original version-checked grading controls. Archived reads remain. No roster, score/reason copies, enrollment, notification or private progress mutation. Separate endpoint preserves existing own overviews and per-Assignment queues. See `docs/course-grading-queue.md`.


Course OWNER feedback/request review queue (2026-10-30): current registered Group OWNER may inspect submitted feedback/request metadata in current canonical Course curriculum. Existing permitted own-submission feedback/request workflows remain included; no-self-grading still applies in the separate grading queue and writer. New default NEEDS_FEEDBACK and existing review concepts use exact latest feedback-version annotations and normalized atomic request source states; UNAVAILABLE is explicit and never current feedback/pending request. Original text/reasons stay source-only, explicit fresh curriculum/Submission navigation. No roster, enrollment, unread tracking, notification or private progress mutation. Existing per-Assignment review contract remains unchanged. See `docs/course-review-queue.md`.


Study OWNER grading queue (2026-10-30): current registered Group OWNER may list other authors' grading metadata across one Study, including standalone Studies with no Course relationship. Seven existing grading filters/default NEEDS_GRADING, direct/rubric0 and exact latest grade-version markers retain their meanings; own submissions stay excluded. Current shared Assignment labels preserve submitted instruction versions. Source selection refreshes Study context and exact original Assignment/Submission reads, preserving original confirmed CAS grading. Archived reads remain, no Course/enrollment, roster, notification or private progress mutation. Separate API preserves published queues and own views. See `docs/study-grading-queue.md`.


Study OWNER feedback/request review queue (2026-10-30): current registered Group OWNER may inspect submitted feedback/request metadata across one Study, independently of Course relationships/enrollment/private progress. Existing permitted OWNER own-submission feedback/request workflows remain included; self-grading stays forbidden in its separate queue/writer. NEEDS_FEEDBACK default and exact latest feedback-version annotations/normalized atomic request states reuse Course review meanings. UNAVAILABLE never means current feedback or pending/resolved request. Current Assignment labels retain submitted instruction versions. Explicit selection refreshes Study context and exact original Submission authority. Archived reads remain; no roster, unread tracking, Course creation, notification or private progress mutation. Existing queues/contracts/writers are preserved. See `docs/study-review-queue.md`.


Group OWNER grading queue (2026-10-31): fresh registered current Group OWNER may inspect other authors submitted grading metadata across all canonical Studies in the same Group/Space, independently of Course relationships/enrollment/private progress. Existing seven filters/default NEEDS_GRADING, score0/direct/rubric and exact latest grade-version marker rules remain unchanged; own submissions stay excluded. Current Study/Assignment labels and Study lifecycle retain original submitted instruction versions. Explicit selection rechecks current Group OWNER then opens exact original Study/Assignment/Submission controls. No roster, Course creation, notification or private progress mutation. Separate endpoint preserves all existing queues and writers. See `docs/group-grading-queue.md`.


Group OWNER feedback/request review queue (2026-10-31): fresh registered current Group OWNER may inspect submitted feedback/request metadata across all canonical Studies in the same Group/Space, independently of Course/enrollment/private progress. Permitted OWNER own-submission review remains included; separate grading queue/writers still forbid self-grading. Existing NEEDS_FEEDBACK default, six overview filters and exact latest feedback-version annotations/normalized atomic request states are preserved. Twenty metadata fields retain current Study/Assignment labels and submitted instruction versions without private text/reason/score copies. Explicit fresh Group OWNER check opens exact original Study/Assignment/Submission. Archived reads remain; no roster, Course creation, unread tracking, notification or private progress mutation. Existing endpoints and source writers are preserved. See `docs/group-review-queue.md`.


Group own-assignment overview (2026-10-31): fresh registered Group participants including VIEWER may inspect shared Assignment metadata across all canonical Studies in the same Group/Space with only their own submission presence. Existing five filters/default OPEN_NOT_SUBMITTED exclude archived Studies from OPEN results and never imply obligation, write permission or private completion. Ten metadata fields preserve current Study/Assignment labels and submitted instruction versions, without peer roster/answer/score copies. Explicit selection rechecks current participation and delegates original Study/Assignment authority using the fresh OWNER flag; VIEWER remains read-only. No Course creation, enrollment, notification or private progress mutation. Existing endpoints and source writers are preserved. See `docs/group-my-assignments.md`.


Group own-submission grading overview (2026-10-31): fresh registered Group participants including VIEWER may inspect only their own submitted work across all canonical Studies in the exact Group/Space, independently of Course relationships/enrollment/private progress. Existing seven filters/default ALL and exact latest grade-version annotations retain Study/Course meanings; direct/rubric0 are grades, old-answer grades remain outdated and historical markers never invalidate newer grades. Fifteen metadata fields preserve current Study/Assignment labels and originally submitted instruction versions, without peer roster/answer/score/reason copies. Explicit selection rechecks participation and opens exact original Study/Assignment with the fresh OWNER flag; VIEWER remains read-only. Archived reads remain; no Course creation, notification or private progress mutation. Existing endpoints and source writers are preserved. See `docs/group-my-submissions.md`.


Group own resubmission-request overview (2026-11-01): fresh registered Group participants including VIEWER may inspect only existing requests on their own submitted work across all canonical Studies in the exact Group/Space, independently of Course relationships/enrollment/private progress. Existing five filters/default REQUESTED and atomic requested/resolved/cancelled/unknown version semantics retain Study/Course meanings; resolved means newer answer arrival, never grading or completion. Fourteen metadata fields preserve current Study/Assignment labels and submitted instructions without peer roster/answer/reason/score copies. Explicit fresh participation opens original Study/Assignment with the current OWNER flag; VIEWER remains read-only. Archived reads retain pending requests without reopening sources. No Course creation, request/notification/private progress mutation; existing endpoints and source writers preserved. See `docs/group-my-resubmissions.md`.


Group own-feedback overview (2026-11-01): fresh registered Group participants including VIEWER may inspect only existing feedback on their own submissions across all canonical Studies in the exact Group/Space, independently of Course relationships/enrollment/private progress. Existing five filters/default ALL and exact latest feedback-version annotations preserve Study/Course meanings, including older-answer withdrawal in RETRACTED and historical marker isolation. Unknown metadata never counts as current. Fifteen metadata fields preserve current Study/Assignment labels and submitted instructions without peer roster/comment/reason/answer/score copies. Explicit fresh participation opens original Study/Assignment with the current OWNER flag; VIEWER stays read-only. Archived reads remain. No Course creation, unread tracking, feedback/notification/private progress mutation; existing endpoints and source writers preserved. See `docs/group-my-feedback.md`.


# Public GitHub integration decision — 2026-11-01

User approved public repository URL linking and explicit bounded recent-commit sync first. Registered PERSONAL PRIVATE Space OWNER only; linking does not imply ownership/contribution. Integration owns connections/imports/revisions/receipts/Outbox; Activity projects imported observations privately through Connect/Kafka. Max20 recent default-branch commits per command, SHA dedup, no full-history completeness claim. Source URLs/IDs are pinned; no tokens/emails/code clone or private repositories. Disconnect retains history. No automatic progress or public publication. See `docs/github-public.md`.


GitHub sync-history follow-up (2026-11-01): current registered PRIVATE PERSONAL OWNER may read the existing immutable completed sync receipts, including zero-import/empty runs and disconnected sources. Newest logical-version paging and current authority apply; reads perform no external GitHub calls or domain/event/progress writes. Original request keys/internal actors are omitted. No schema, published event or privacy-policy change. See `docs/github-sync-history.md`.


GitHub private learning bundle (2026-11-01): explicit imported commit→Roadmap node evidence, private memo/CAS/history/retirement, optional same-node manual StudySession pin, reverse commit→Learning browse and both eventual Activity streams. Registered PRIVATE PERSONAL OWNER only; Integration immutable imported facts remain usable after disconnect, Learning owns evidence and links. New additive typed APIs/events preserve published NOTE/CONCEPT/MEDIA contracts and shared identities. No contribution attribution, progress inference or public publication. See `docs/github-learning-bundle.md`.


GitHub PR/Issue observation bundle (2026-11-01): registered PRIVATE PERSONAL OWNER explicitly imports bounded recent public PR/Issue metadata with separate per-kind ghwN CAS/receipts, current filters, immutable local observation histories and both eventual Activity streams. Integration owns facts and same-owner transactions; existing repository/commit/learning contracts and identities stay unchanged. Issue API PR markers are excluded, missing bounded-page rows retained, closed PR never implies merged, no authorship/progress inference or public publication. See `docs/github-work-bundle.md`.

Local environment recovery decision (2026-11-01): user approved WSL20GB RAM/8GB swap and, after an external Docker GUI factory reset and confirmation of no backup, a fresh local environment without restoring old data. Preserve the existing service/storage/event architecture and pinned versions. Old runtime proof is historical; do not claim old data restored or fresh deployment verified until rebuilt and checked. Resource Saver is disabled following a WSL freeze; host WSL/Docker responsiveness was verified before the fresh rebuild. See `docs/local-wsl-resources.md`.

Fresh local runtime and GitHub PR/Issue completion (2026-11-01): approved fresh environment verified with2 Ready nodes,14 Ready applications,10 RUNNING Outbox connectors/tasks,76 domain Registry artifacts,363 fresh backend checks and17 browser scenarios. Original data was not restored. Source/UID/image evidence is recorded in `.local/github-work-bundle-validation.json`; current remaining work is summarized in `docs/current-status.md`.


Local backup/recovery decision and verified drill (2026-11-01, user requested): operator-only encrypted cold backup outside Docker, all7 PVCs plus matched Kubernetes configuration/credentials/Kafka IDs and pinned workload images; restore into a new isolated cluster with unchanged service/data/event boundaries. Actual11,266 file hashes and14 DBs/282 table fingerprints matched before apps started. Existing authentication, private history/files/isolation and a new Outbox/Connect/Kafka/Activity update passed. Source2-node environment returned with14 Ready apps,10 RUNNING connectors and original PVC UIDs; clone retained stopped. Current dataset only, no pre-reset data recovery or off-host copy claimed. No implicit schedule/retention deletion. See `docs/local-backup-recovery.md` and `docs/local-backup-recovery-validation.txt`.

Central observability bundle (2026-11-01, next large authorized package): the documented OpenTelemetry/Prometheus/Loki/Tempo/Grafana stack is deployed with Alertmanager,7 local alert rules and a read-only scoped metadata observer. All13 Java services use digest-pinned agent2.33.1 over mutual-TLS OTLP HTTP/protobuf,10% trace sampling, bounded queues and pre-persistence data minimization. Gateway active span identity agrees with existing X-Trace-Id; published event traceId remains stable for asynchronous consumer-log correlation without fabricating parent-span ancestry or changing event contracts. Grafana HTTPS8446 is operator-only; external alert delivery is unconfigured. Actual privacy/mTLS/network/RBAC/browser checks,239 Gateway tests,10 backup tests, bounded load and real service/Collector outage-recovery passed. Original14 applications/10 connectors/76 Registry artifacts and service Deployment UIDs remain intact; monitoring7 deployments are Ready. Future cold backups inventory13 PVCs including six monitoring volumes; the earlier7-volume restore remains the actual restore proof. No new domain API, schema/migration, cross-service DB access, event publisher, public data or AI. See `docs/observability.md` and `docs/observability-validation.txt`.


Public knowledge contribution/verification bundle (2026-11-01, user requested as one large package): Knowledge owns immutable public Alias/typed Claim/Source/Evidence/Mapping/Verification snapshots, explicit CREATE/UPDATE/RETIRE proposals, designated independent review/atomic merge and revision histories. Private working rows and published private/Concept contracts remain unchanged; public Concept IDs are reused and references pin inspected same-public-Space versions. DRAFT/SOURCED/COMMUNITY_REVIEWED/EXPERT_VERIFIED assessments never transfer to a new Claim revision; changed/retired grounds require review. CANDIDATE/CONFIRMED/OFFICIAL mapping is independent. Space owns separate operator-granted CLAIM_EXPERT_VERIFY and MAPPING_OFFICIAL_REVIEW capabilities, each additionally requiring a current designated reviewer; membership alone grants neither. Qualification/provenance is an operator assertion, not automatic credential verification. New standalone public-record/capability Protobuf events retain Outbox/Connect-only publication. Typed browser forms, public consent, review queues, bounded canonical Alias search/relation browsing and390px reads passed actual HTTPS/Keycloak tests, including mid-workflow capability revocation, stale merge and private isolation. Actual canonical and operator CDC bytes were checked; test authority retired, original14 apps/13 PVC/service Deployment UIDs retained. No new package, clinical dataset, full-text import or AI. Public qualifiers, curated licensed content and broader projections/design remain separate work. See `docs/public-knowledge-workbench.md` and `docs/public-knowledge-workbench-validation.txt`.


Public qualifiers and discovery decision/completion (2026-11-01, user requested as one large package): Knowledge extends the same independent proposal/review/CAS merge/history policy to typed qualifiers pinned to one immutable Claim revision. Verification separately captures all qualifier identity versions including retirements; changed scope or referenced Concept/source context requires fresh review and re-sourcing. Existing public-record/Concept/private contracts remain unchanged. A new standalone public-knowledge v1 stream feeds Brain Query-owned durable PostgreSQL work, a fixed public OpenSearch index and isolated JanusGraph pub* namespace. Fresh Space/Identity and bounded current public canonical checks guard all search/graph results; no private or pending data is projected. Public-only fenced canonical rebuild plus captured Kafka replay preserves private reads and later CDC continuity. Twelve revision-pinned Wikidata CC0 label/identifier entries are previewed and explicitly proposed, never automatically published or clinically verified.649 backend checks, three browser scenarios and a real concurrent-write derived-model rebuild passed;79 Registry artifacts/10 CDC tasks,14 apps/7 monitoring/13 JVMs and original13 PVC/domain Deployment UIDs were preserved. Measured Grafana saturation was addressed through a local-only1 CPU/768Mi resource overlay. No new service/dependency, cross-owner DB access, direct Kafka publisher, AI or clinical dataset/full-text import. Substantive content curation, full frontend design and production delivery remain separate. See `docs/public-discovery.md` and `docs/public-discovery-validation.txt`.


Shared workspace and graph design completion (2026-11-02, next large package using the user's accepted Obsidian references): the same static Next.js application gains a shared dark/light workspace for Personal/Knowledge/Roadmaps/Groups, compact desktop/mobile navigation and an accessible Ctrl/Cmd+K palette. Personal Brain presents its graph/editor first and groups existing features below. Four graph views use deterministic bounded force layout, type/degree presentation, neighbor highlighting, local drag/pan/zoom/fit and keyboard controls; dense names are bounded and light-mode contrast remains readable. Browser storage contains only explicitly changed UI preferences. Domain authority, draft clearing, private defaults, public review/capabilities, endpoints, migrations and published events remain unchanged. Twelve exact-image HTTPS browser scenarios, source/image comparison,390px visual checks and unchanged14 app/13 PVC identities passed; only Gateway was rolled. No new dependency, service, dataset, AI or whole-cluster restore claim. See docs/workspace-design.md and docs/workspace-design-validation.txt.


Focused personal workspace completion (2026-11-02, next large recommended package): the existing static client adds allowlisted category-only focus URLs, history navigation, first-use area mounting and retained hidden/inert drafts. Full overview remains available; existing private-session/Space keys clear local data on original boundaries. Note composing gains a full-width layout, selection formatting, IME-safe explicit keyboard save and existing safe side-by-side Markdown preview. Owner endpoints, strong versions/conflict locks, restore policy, private/public authority, browser content-storage policy and service/event/schema boundaries are unchanged. Twenty-five exact-image HTTPS browser scenarios, including real registered multiple Spaces and hidden draft clearing, passed with source/image/runtime comparison. Only Gateway rolled;14 apps/7 monitoring/13 JVMs/10 CDC and original13 PVC identities remained healthy. No new dependency, AI, automatic save/publication or learning mutation. See docs/workspace-flow.md and docs/workspace-flow-validation.txt.


Role-scoped Group workspace completion (2026-11-02, next large recommended package): owned and participating Group entries share one workboard with ten task categories, category-only sidebar/keyboard/history navigation, first-use mounting and hidden/inert in-scope drafts. One active selection, coordinated entry controls, fresh participation/membership checks, actor/role/version boundaries, failed-check draft clearing, recovered URL normalization and late-response/actual401 handling preserve private source authority. Existing explicit original Submission/Study/Assignment controls, immutable instruction versions, CAS, consent, VIEWER and no-self-grading policies remain unchanged. Sixteen exact-image HTTPS browser scenarios passed, including membership, four own-work queues, OWNER review/grading, rubric/files/notifications/Courses/private-progress regressions and390px OWNER/VIEWER views. Source/image/runtime comparison preserved14 apps/7 monitoring/13 JVMs/10 CDC and original13 PVC/Deployment identities; only Gateway rolled. No new dependency, API/event contract, schema/migration, service, AI, automatic sharing/publication or learning mutation. See docs/group-workspace.md and docs/group-workspace-validation.txt.


Focused public knowledge workspace completion (2026-11-02, next large recommended package): merged public search/graph opens first, with category-only sidebar/keyboard/history navigation to original Concept contribution/review and typed record/evidence/verification tasks; complete overview remains available. First-use mounting and hidden/inert drafts are keyed by current principal/kind, domain and propose/review authority. Fresh identity/catalog checks, domain/role/visibility changes, failed-check recovery, late-response isolation and actual401 prevent old drafts from carrying across scopes. Explicit public write commands coordinate pending navigation. Original source versions/consent/independent review/CAS, no-self-approval, private defaults and separate expert/official capabilities remain unchanged. Eleven exact-image HTTPS browser scenarios passed, including original Concept/Roadmap and typed workbench privacy/capability/pin regressions, real reviewer-revocation draft clearing and390px visual checks. Read-only audit confirms all temporary test authority inactive; source/image comparison preserved14 apps/7 monitoring/13 JVMs/10 CDC and original13 PVC/Deployment identities; only Gateway rolled. No new dependency/service, domain API/schema/event, dataset, AI or automatic sharing/progress/publication. See docs/knowledge-workspace.md and docs/knowledge-workspace-validation.txt.


Focused Roadmap workspace completion (2026-11-02, next large recommended package): browse-first reviewed graphs, focused ranking/contribution/review tasks, category-only sidebar/keyboard/history and an original private-learning entry. Independent browse/editor state retains in-scope drafts/nodes/preview while exploring another graph; explicit update pins the inspected source. Current principal/kind/domain/propose-review keys, fresh catalog/identity checks, hidden/inert state, failed-check recovery, late-response isolation and real401 clear old scopes. Per-command tokens coordinate pending publication/review/adoption/rating writes. Initial private-catalog404 preserves only empty preparation/reload guidance, with all write/401/403 authority unchanged. Original preview/consent/review/no-self/CAS, versioned ratings and once-per-source-version private adoption/progress policies remain intact. Eighteen exact-image HTTPS browser scenarios passed, including private graph/editor, adoption/provenance/progress, real registered rating failure UI, common graph/design and registered public review/revocation regressions. Temporary authority is inactive; source/image/runtime comparison preserved14 apps/7 monitoring/13 JVMs/10 CDC and original13 PVC/Deployment identities; only Gateway rolled. No new dependency/service, domain API/schema/event, dataset, AI or automatic sharing/progress/publication. See docs/roadmap-workspace.md and docs/roadmap-workspace-validation.txt.


Delivery foundation and local GitOps completion (2026-11-06, next large recommended package): the existing GitHub Actions/GHCR/Argo CD direction now has full-SHA-pinned workflows, thirteen independent owner-database test/build jobs, exact-source frontend artifacts, same-archive HIGH/CRITICAL scan-before-push gates and complete registry-digest release locks. GitOps stages immutable values, verifies existing configuration-commit contents and guards stale/concurrent pointer updates; Applications remain manual without prune/deletion finalizers. Full namespace-scoped Argo CD3.6.1 performed actual isolated Git sync, drift detection/repair, commit upgrade and Git-revert rollback using the existing service chart and cached Gateway OCI manifest. Controller authority cannot change brain-apps or data PVCs.1,873 backend checks passed with86 explicit external-condition skips,17 delivery checks passed, actionlint validated both workflows, and the pinned Node frontend CI reproduced52 hashed assets in an isolated Git fixture. Original14 app/7 monitoring Deployment specs, UIDs/images and13 PVC identities were retained;13 JVMs/10 CDC connectors and five trusted-CA HTTPS entry points passed current health checks. The current Gateway vulnerability scan correctly blocks release with4 CRITICAL/12 HIGH detections; no exploitability validation or exception is claimed. No GitHub remote/hosted Actions/GHCR publication, existing-app adoption, production readiness, canonical data reset, live CDC rerun or source-repository commit occurred. Remediation/rescan and external repository connection remain prerequisites. No new domain service/API/schema/event, data sharing, AI or Valkey replacement. See docs/delivery.md, docs/delivery-validation.txt and docs/delivery-scan-findings.json.


Dependency/runtime baseline completion (2026-11-06): all13 independent Java services now run the scanned Boot4.1.2/Temurin25.0.5+8 UBI10 candidate by immutable digest. Two own-GAV graph compatibility libraries replace embedded Jackson and backport the upstream driver-Guava capacity clamp without changing JanusGraph1.1/CQL storage semantics, service ownership, contracts or publication flow.1,876 backend checks (86 explicit gates skipped),13 exact-image scans and67 browser scenario executions across four overlapping suites passed, with real storage/Kafka checks and original private data/credential continuity. All14 apps/7 monitoring/13 JVMs,10 CDC connector/tasks,79 source-matching Registry artifacts and original13 PVC/14 app Deployment identities are verified. The patched isolated GitOps probe also completed sync/drift/upgrade/Git-revert rollback. Remote delivery, Valkey and remaining V1 audit work are still open. See `docs/java-runtime.md` and `docs/current-status.md`; detailed security evidence is in the managed repository artifact collection.


Public candidate cache completion (2026-11-06, continued recommended V1 package): a separate platform Valkey9.0.7 chart and Brain Query-owned short-lived public search candidate cache now implement the existing Valkey decision. TLS CA/DNS verification, scoped ACL/key prefix, private network, bounded memory/clients/payload/workers/deadline and failure cooldown preserve optional-cache behavior. Only public IDs/kinds/versions and page metadata are cached for5 seconds; authority, canonical state/version/context and readiness checks run fresh. Private content/credentials/permissions and final responses are excluded.59 executed Query checks,16 exact-image transport/network checks,3 source-bound browser scenarios, real cache-only outage/recovery and private Note CDC/revocation passed; both exact artifact scans show0 HIGH/CRITICAL. Existing14 app/7 monitoring/13 JVM services,10 CDC tasks, all13 PVCs and unrelated specs remain intact; only Query rolled. No service boundary, domain API/event/schema, canonical persistence, public publication, dataset or AI changed. Broader V1 source/accessibility/performance/remote delivery/content/operations audit remains active. See `docs/public-search-cache.md`, `docs/public-search-cache-validation.txt` and `docs/current-status.md`.


Public tables/accessibility/client graph completion (2026-11-07): loaded public graph/search tables, typed summaries, native sorting, canonical detail and applied-filter pagination now complete general public table browsing. Mobile/shell/modal focus, full-label interactive graph semantics and measured contrast fixes passed30 settled-screen axe states. Bounded same-origin graph Workers and identical yielding fallback preserve canonical data, private defaults and all13 MSA boundaries. Gateway243 checks,20 delivery checks and76 HTTPS browser scenario executions passed, plus fresh exact-commit Linux frontend CI and desktop/mobile inspection. Next16.4.5 dependency audit and exact Gateway HIGH/CRITICAL scan are clear; stale generated/compiled assets are replaced reversibly. Only Gateway image changed; original13 PVCs/Deployment identities and14 apps/7 monitoring/13 JVMs/10 CDC remain healthy. This is scoped local evidence, not complete WCAG, server-load or production certification. Remaining V1/source/delivery/content/operations work continues. See `docs/public-tables-accessibility.md`, `docs/public-tables-accessibility-validation.txt` and `docs/current-status.md`.


Current-stack local operations completion (2026-11-07): core HTTPS profile600 seconds/2,640 reads/60 CAS writes passed isolation, fresh revocation and real unique CDC/Activity version tails. Actual encrypted restore covers current13 PVCs/39 runtime images,18,304 file hashes and14 DBs/292 table count/hash rows plus local GitOps/observability. Old login/guest/history/sealed binary/public search, new isolatedv3,79 Registry artifacts/10 tasks and43 workloads passed. Source originalv2,43 workload/13 PVC identities/specs and five trusted-CA HTTPS entries returned; clones remain stopped/retained.15 recovery boundary checks cover expanded inventory, full runtime image content and order-independent exact table fingerprints. No service/API/event/schema/image-version change, public publication, data deletion, new dependency or AI. This is local evidence, not production HA/PITR/SLA/off-host delivery. See `docs/operations-validation.md` and `docs/operations-validation.txt`.


V1 source/evidence reconciliation (2026-11-07): source and substantive tests are mapped against original requirements, with historical proof kept separate. Visible Space/public search/Note-Concept history response declarations were corrected without changing wire behavior;51 delivery/contract/installer/curation checks, six actual HTTPS response checks and33 current personal browser scenarios passed. Forty-two historical tag-based feature installers now stop before side effects on current digest-pinned/malformed selected values. Original43 workload and13 PVC identities/specs,13 JVMs/14 apps/10 CDC and five trusted HTTPS entries remain. No application image, service ownership, migration/event version, privacy/public authority or AI changed. Twenty-one source-linked factual authoring drafts cover all seven initial topics with scope/rights notes; they remain unpublished, unresolved and independently unreviewed, with no importer, expert assertion or project license assignment. Explicit proposal/review integration and deterministic Learning guidance beyond ratings remain open, as do actual hosted delivery and external choices. See docs/v1-source-audit.md and docs/v1-source-audit-validation.txt.


Deterministic Learning guidance and sourced authoring completion (2026-11-07): private saved-graph/manual-progress guidance now ranks eligible nodes and explains direct required/recommended predecessors without changing progress. The existing21 sourced drafts now have canonical-ID/version inspection, explicit one-item proposal/consent and independent-review handoff in public discovery.188 Learning checks (14 explicit existing gates skipped),245 Gateway checks,58 delivery/contract checks and53 final HTTPS scenario executions passed, plus original auth/history/binary continuity. Both exact images scan0 HIGH/CRITICAL; 56 frontend assets match the stable-source CI fixture. Original43 workload identities/specs except two image fields,13 PVCs, 13 JVMs/14 apps/7 monitoring/10 CDC and five trusted HTTPS entries are retained. Temporary test authority is inactive. All21 supplied drafts remain unpublished and independently unreviewed; only isolated Development fixture metadata is merged, with the test Claim left SUBMITTED. No new dependency/service/migration/event, AI, automatic publication or progress mutation. Actual hosted delivery/repository/license/adoption and required external operation choices remain open. See Learning guidance (docs/learning-guidance.md), sourced authoring (docs/public-curation.md) and validation (docs/guidance-curation-validation.txt). Whole V1 remains active.


V1 final source/Unicode correction audit (2026-11-07): identified native UTF-16/code-point and Java whitespace mismatches are corrected across private/public/Group inputs without changing owner contracts or data policy.73 final browser scenario executions,245 Gateway checks and70 executed Python checks passed, with one explicit OS skip and proper Graph2-executed/1-skip evidence.750 owner source files and106 Query application classes provide explicit continuity; original43 workload/13 PVC identities and all13 services remain healthy. Only Gateway image changed; no dependency/API/schema/event, automatic sharing/progress/publication or AI added. Actual hosted delivery/license/repository/adoption and designated factual-content review remain incomplete. See docs/v1-final-audit.md, docs/unicode-contracts-validation.txt and docs/current-status.md.
