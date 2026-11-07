# V1 source and current-evidence audit — 2026-11-07

This audit reconciles `AGENTS.md`, the original V1 scope and accepted decisions in
`PROJECT.md` with current implementation, substantive tests and actual local
evidence. It does not redefine V1 around the latest passing package. Source
inspection proves implementation exists; a dated test report proves only the
paths and environment it exercised. The full V1 goal remains active.

## Implemented requirements and evidence

Java source/test filenames below are under the named service's
`src/main/java/io/knowledgebrain/<package>` and corresponding `src/test/java`
directory. These are concrete starting points, not an assertion that a filename
alone proves a requirement. Integration suites exercise owner databases,
concurrency, stale versions, transactional rollback and foreign-Space denial.

| Requirement | Current source and substantive tests | Evidence scope |
| --- | --- | --- |
| Private Concept, Alias, typed Claim, Qualifier, Source, Revision and Mapping | Knowledge `ConceptStore`, `AliasStore`, `ClaimStore`, `QualifierStore`, `SourceStore`, `ExternalMappingStore`; corresponding integration suites including `TypedClaimStoreIntegrationTest` | Current source inspected; all-service runtime baseline has real owner-DB regressions. The new browser audit exercises exact typed Claims, qualifiers and source citations. |
| Public Contribution, independent Review/Merge, Verification and Mapping | Knowledge `ContributionStore`, `PublicRecordStore`, `PublicKnowledgeController`; `PublicRecordStoreIntegrationTest` covers self-review denial, stale/foreign pins, rollback, verification reset and separate mapping authority | Implemented. Workbench/discovery runtime proofs and current focused-workspace proofs remain separately dated; this package does not claim a new full typed-public-merge run. |
| Public search, graph and table views | Query `PublicDiscoveryController`, `PublicProjectionStore`, `PublicProjectionIntegrationTest`, `PublicStorageIntegrationTest`; client `public-discovery.tsx`, `public-knowledge-table.tsx`, `public-tables.spec.ts` | Current exact-image table/accessibility proof plus actual current search/graph response checks. Real rebuild/concurrent-tail proof remains its original dated record. |
| Markdown Notes, history and concurrent editing | Content `NoteController`, `NoteStore`, `NoteStoreIntegrationTest`; browser `editor`, `markdown`, `note-restore` | Current exact-image workspace-flow suite covers these paths. |
| Stable-ID Wiki Links, unresolved links and backlinks | Content `WikiLinks`, `NoteLinkStore`, `NoteLinksIntegrationTest` | Rename stability, later resolution, ambiguity, retirement, isolation and atomic rollback are tested. Original full Wiki browser proof is historical; runtime upgrade/restore preserves old reference data. |
| Space tags and typed Note properties | Content `NoteMetadataStore`, `MetadataInputTest`, `MetadataStoreIntegrationTest`; browser `metadata`, `property-filter`, `property-range`, `property-tag` | New current-image browser audit covers exact decimals, independent versions, entire-collection filtering, conflicts and privacy. |
| Permission-filtered Note Graph | Query `NoteGraphController`, `NoteGraphApiTest`, `NoteGraphStoreIntegrationTest`; browser `graph` | Current workspace-flow browser proof and real Cassandra-backed integration evidence. |
| Daily Note / Journal | Content `DailyDates`, `DailyNoteController`, `DailyDatesTest`, `DailyStoreIntegrationTest`; browser `journal` | New current-image audit covers explicit timezone, immutable binding, reopen, paging, ordinary-Note exclusion and isolation. |
| Note attachments through Media/MinIO | Content `NoteAttachmentsController`, `AttachmentStoreIntegrationTest`, `AttachmentApiTest`; Media `MediaStore`, `MinioStorage`, their integration tests; browser `attachments` | New current-image audit performs actual TLS MinIO upload/resume/download and sealed-byte checks, CAS/history, foreign denial and revocation. |
| Projected Note search | Query `NoteSearchController`, `NoteSearchApiTest`, `NoteSearchStorageIntegrationTest`; browser `search` | Current workspace-flow proof; real TLS OpenSearch integration checks stale events, role limits and isolation. |
| Explicit Note-to-Concept links | Content `NoteConceptsController`, `NoteConceptStore`, `ConceptLinkStoreIntegrationTest`, `ConceptLinkApiTest`; browser `concepts`, `concept-notes` | New reverse-reference browser audit plus current nonempty link-history response checks; Knowledge IDs stay canonical. |
| Questions, Decisions, Bookmarks and Concept links | Content `QuestionStore`, `DecisionStore`, `BookmarkStore` and corresponding integration suites; browser `questions`, `decisions`, `bookmarks`, `record-concepts`, `record-network` | New current-image audit exercises create retry intent, immutable history, real conflicts, archive, scoped paging, independent drafts, graph/search and revocation. |
| Timeline | Activity `ActivityController`, `ActivityStoreIntegrationTest`, `ActivityClickHouseIntegrationTest`, `ActivityApiTest`, `ActivityRecoveryTest` | Current operations profile observes all 31 versions per actor through actual Outbox/Connect/Kafka/Activity; real ClickHouse isolation/replay tests remain explicit. |
| Roadmaps, graph nodes/edges, public review/adoption | Learning `RoadmapGraph`, `RoadmapStore`, `PublicRoadmapStore`, `RoadmapAdoptionStore` and corresponding integration suites | Cycle/bound checks, graph revisions, independent public review, atomic merge and one adoption per source version exist. Current Roadmap workspace proof is separate from older full feature proofs. |
| Study sessions, manual Progress, Evidence and session links | Learning `LearningStudyStore`, `LearningEvidenceStore`, `EvidenceSessionStore`; their integration suites plus `ConceptEvidenceStoreIntegrationTest` and `MediaEvidenceStoreIntegrationTest` | Implemented with immutable source pins, scope/rollback/concurrency checks and independent progress. No automatic completion inference. |
| Ratings and ranking | Learning `RoadmapRatingStore`, `RoadmapRatingStoreIntegrationTest` | Deterministic exact-mean/count/UUID ordering and unrated-last behavior; popularity remains independent of verification. See the scope note below. |
| Goals, Tasks, Schedules, Reminders, Recurrence | Planning `GoalStore`, `TaskStore`, `ScheduleStore`, `RecurrenceRules`, `RecurrenceStore`, `ReminderStore`, `ReminderDeliveryStore` and their integration suites | Explicit mutations, timezone/DST rules, immutable source pins, bounded atomic recurrence, due dispatch/retry and suppression after source changes are implemented. |
| Groups, Studies, Courses, Assignments, Discussions and Submissions | Collaboration stores and corresponding integration suites, including `SubmissionInstructionsStoreIntegrationTest`; current `group-workspace` browser proof | Role/current membership checks, pinned instructions, immutable attempts, independent histories, scope and rollback are implemented. |
| Approved grading, rubric, submitted files and progress consent | `SubmissionGradeStore`, `AssignmentRubricStore`, `RubricGradingStore`, `SubmissionFilesStore`; Learning `CourseProgressStore`, `CourseProgressShareStore`; corresponding integration/API suites | Frozen criteria, no self-grading, manual/derived score choice, version-specific private files, counts-only explicit OWNER sharing and epoch invalidation remain intact. |
| Guest/authentication/claiming/multiple Personal Spaces | Identity `GuestSessionController`, `GuestRegistrationStore`, `OidcLoginService`, `OidcProvider`; Space `PersonalSpaceStore`, V2 migration; `GuestSessionIntegrationTest`, `RegisteredLoginIntegrationTest`, `GuestClaimIntegrationTest` | Current real login/guest recovery and multiple-Space browser evidence. Guests do not become normal Keycloak accounts. |
| GitHub public repository/commit/PR/Issue import and learning links | Integration `GitHubController`, `GitHubWorkStore`, `GitHubWorkSecurityTest`, `GitHubWorkStoreIntegrationTest`; Learning `GitHubEvidenceStore`, `GitHubReferenceClient` and their tests | Bounded explicit imports, registered-owner checks, immutable provenance, receipts/history and private evidence exist. Private GitHub App support is future scope. |
| Thirteen services, owner DBs, Java25, REST/OpenAPI, UUIDv7 and events | Parent POM/service modules, owner DB initialization/isolation verifier, `UuidV7Test`, owner Outbox tests and versioned Protobuf contracts | Current all-service runtime proof and 79 byte/rule-verified Registry artifacts with 10 running CDC tasks. No application direct Kafka producer was found in the bounded source review; that search alone is not a proof of all architecture invariants. |
| Derived storage, Valkey and rebuildability | Query rebuild runners and durable projection stores/tests; Activity recovery; delivered cache and operations runbooks | Canonical ownership and fresh authorization remain. Current restore proves recovered stores and new CDC, not a newly executed canonical rebuild of every projection. |
| Observability, delivery and recovery | Existing observability, Java-runtime, delivery, cache and operations evidence | Local monitoring, failure behavior, digest rollout, isolated GitOps rollback, sustained API profile and current 13-volume restore are verified. Actual hosted delivery remains open. |

## Corrected response contracts

Three existing wire responses contradicted their OpenAPI declarations:

- By-ID Space reads can return visible Group roles. Separate `VisibleSpace`
  schemas now describe that response in Space and Gateway contracts; the Personal
  endpoint remains `PERSONAL`/`PRIVATE`/`OWNER`.
- Public search returns a paged `SearchPage`, while graph returns `GraphView`.
  Their success schemas are now separate; error responses are preserved.
- Note-to-Concept history returns at most ten audited `ReferenceRevision`
  objects. It no longer reuses the current snapshot schema. Gateway references
  the corrected canonical history response.

Contract patch versions were incremented. Regenerate affected clients from these
corrected descriptions; application wire behavior, authorization, DB migrations
and Protobuf event versions are unchanged. Eleven focused CI checks include
regression controls that reject the two old incompatible response shapes.
`Verify-LocalApiContracts.py` also checks six actual current-image HTTPS response
cases with fresh private fixtures, then revokes its own new guest session. These
are targeted shape checks, not a general OpenAPI/JSON Schema conformance claim.

## Current validation and operation

```powershell
python -m unittest discover -s tests/delivery -v
python scripts/Verify-LocalApiContracts.py
python scripts/Run-GraphWebValidation.py --v1-personal-audit --no-images
```

Use the existing reviewed Python/PyYAML and Helm prerequisites. The serial
16-file browser selection passed 33 scenarios on the existing Gateway digest.
It covers newly reconciled personal-feature paths without changing deployed
images. Relevant test sources and actual Job identity are hash-bound in the
private `.local/v1-audit/evidence-index.json`; [validation](v1-source-audit-validation.txt)
records scope and final environment checks.

Forty-two historical tag-based application installers now stop before side
effects when their selected local values are digest-pinned or malformed. Their
original tag-only paths remain historical; maintained infrastructure installers
are unaffected. See [inventory and supported current workflow](local-feature-installers.md).
This guard does not replace image scans, imports, rollout or rollback checks.

## Remaining work and scope boundaries

The [draft curation package](../data/public-curation/README.md) now contains 21
short Korean factual statements across all seven initial topics, eight primary
sources and 33 unresolved local terms. Eight offline boundary tests cover its
draft-only state, references, source/rights notes and volume limits. It supplies
authoring material without public, independent-review or expert assertions.
Current ID/version selection, explicit preview/proposal and independent review
remain open; the twelve starter names/IDs alone were insufficient.
Actual source publication, repository selection, project licensing, hosted
Actions/GHCR and reviewed GitOps adoption require their concrete delivery review.
The GitHub connector can read the current account, but no matching owned project
was observed in the inspected accessible listing and no local remote is set;
that is not proof that no possible organization repository exists.

`PROJECT.md` section9 requires any V1 recommendation to be deterministic/rule-based.
Implemented rating ranking is deterministic, but it does not supply next-study
guidance. The older ratings milestone deferred that work; it cannot remove an
original V1 requirement. No distinct next-study selector or its tests are present.
Keep rule-based Learning guidance in the remaining implementation audit, using
existing graph/manual-progress ownership and explicit user changes; do not count
ranking alone as proof that it is delivered.

All four declared Space kinds remain in the canonical model. Organization
provisioning/domain workflows are not delivered, and no organization administration
UI requirement is inferred from that enum. Media deletion/quota reclamation and
physical-version garbage collection remain documented limitations. Native mobile,
audio, private GitHub App, AI and site-level production HA are separate future
scope. Production SLO/off-host/retention decisions remain distinct from the
verified single-host operations profile.


## Subsequent implementation evidence

The [Learning guidance and sourced-authoring validation](guidance-curation-validation.txt)
subsequently closes the two implementation gaps identified in this snapshot:
explicit current-ID/version proposal preparation and deterministic private next-study
guidance. The original counts and source observations above remain historical.
All21 supplied drafts still await actual designated content review and publication;
remote delivery and required external choices remain open. See the current
[completion index](v1-completion.md) for the authoritative remaining scope.
