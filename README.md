# Knowledge/Brain Platform

[Current delivery status](docs/current-status.md) — latest verified scope and remaining large work packages.

[V1 source/evidence audit](docs/v1-source-audit.md) — implemented requirements, corrected API contracts and current versus historical validation.

[Local backup and restore](docs/local-backup-recovery.md) — encrypted cold backup, isolated restore and verified recovery evidence.

[Local monitoring](docs/observability.md) —13-service telemetry, Grafana HTTPS8446, alerts and verified outage recovery.

[Public knowledge workbench](docs/public-knowledge-workbench.md) — explicit public facts, review/merge, independent verification/mapping authority and audit.

Architecture authority: AGENTS.md and PROJECT.md. Current implementation and verified scope are recorded above; full V1 completion remains under audit.

## License and public delivery

Original project software is licensed under [Apache-2.0](LICENSE); retain the
[NOTICE](NOTICE) and existing component notices. Starter metadata and factual
authoring drafts retain their separate provenance and rights records. See the
[license scope](docs/project-license.md) and [approved delivery batch](docs/remote-delivery-execution.md).
The factual drafts remain independently unreviewed; source publication does not
merge them into canonical public Knowledge or establish clinical verification.

## Build and run

Use a maintained JDK 25 or newer. Both compiler release and the initial runtime baseline are Java 25 LTS. Set JAVA_HOME to the real JDK installation directory before using the wrapper; do not rely on the Oracle javapath launcher. Virtual threads are enabled for Spring Boot task execution and supported servlet request handling. Performance benefits must be measured on the implemented workloads.

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-25"
./mvnw.cmd install
./mvnw.cmd -pl services/identity-service spring-boot:run
```

On Unix use `./mvnw`. Each module produces a separately executable Spring Boot jar. All default to port 8080 inside their own process/container; set PORT when running several directly on the host.

Default skeletons expose only health, liveness and readiness. Explicitly enabled Identity/Space/Content APIs support guest browser credentials, authorized private Space reads and private Markdown Notes with revisions, optimistic concurrency, Wiki Links/backlinks, Space-scoped tags, typed properties, Daily Notes/Journal, private Media uploads and Note attachments with Outbox events. Gateway exposes the declared APIs on one HTTPS origin; undeclared domain and management routes remain denied. No database or event backbone is emulated.

## Implementation sequence

1. Build foundation: separate service artifacts and safe probes (this change).
2. Platform baseline: k3d, Helm/GitOps, all required storage/event/auth components, service-owned database credentials, OpenTelemetry and the prescribed observability stack; record dependency versions/licenses and measure memory use on the 32 GB machine.
3. First end-to-end flow: guest principal -> private Personal Space -> private note -> transactional outbox -> Kafka -> idempotent activity/read projection, with retry and cross-space access tests.
4. Registered identity and claiming existing guest spaces; then knowledge, contribution/review, learning, planning, collaboration, media and GitHub integration.

These are delivery phases, not architecture alternatives. Local Kubernetes validation is required before calling a deployable milestone complete. All 13 service boundaries and required infrastructure remain in the intended architecture.

Local cluster bootstrap and remaining infrastructure steps: [docs/local-platform.md](docs/local-platform.md).

Identity Protobuf contracts, transactional Outbox and actual PostgreSQL validation: [docs/identity-outbox.md](docs/identity-outbox.md).

Debezium/Kafka Connect deployment and Outbox transport checks: [docs/connect-bootstrap.md](docs/connect-bootstrap.md).

Guest Personal Space consumer and durable Inbox: [docs/space-provisioning.md](docs/space-provisioning.md).

HTTPS guest browser sessions and authorized Space reads: [docs/guest-session-api.md](docs/guest-session-api.md).

Common HTTPS Gateway and local access: [docs/gateway-api.md](docs/gateway-api.md).

Accepted Personal Brain note capabilities and sequence: [docs/personal-notes-roadmap.md](docs/personal-notes-roadmap.md).

Private Markdown Note API and deployment: [docs/markdown-notes.md](docs/markdown-notes.md).

Wiki Links and private Backlink API: [docs/wiki-links.md](docs/wiki-links.md).

Space-scoped tags and typed property API: [docs/note-metadata.md](docs/note-metadata.md).

Private Daily Note and date-ordered Journal API: [docs/daily-notes.md](docs/daily-notes.md).

Private attachments, Media lifecycle and direct MinIO upload: [docs/note-attachments.md](docs/note-attachments.md).

Local read-only Kubernetes web UI: [docs/headlamp.md](docs/headlamp.md).

Private OpenSearch infrastructure and Brain Query search adapter: [docs/opensearch.md](docs/opensearch.md).

Live Note-to-search Kafka projection, durable Inbox/queue and recovery: [docs/note-projection.md](docs/note-projection.md).

Canonical Content snapshot and lost search-index recovery with Kafka tail replay: [docs/note-search-rebuild.md](docs/note-search-rebuild.md).

Private user Note search, fresh Space authorization and Gateway routing: [docs/note-search-api.md](docs/note-search-api.md).

Private Cassandra and live JanusGraph Note/link projection with canonical rebuild and authorized Graph API/UI: [docs/note-graph.md](docs/note-graph.md).

Private Graph API and Next.js Graph View: [docs/note-graph-api.md](docs/note-graph-api.md).

Private Knowledge Concepts and explicit Note-to-Concept links: [docs/note-concepts.md](docs/note-concepts.md).

Registered Keycloak login, guest Space claiming and multiple private Space selection: [registered-login.md](docs/registered-login.md).

Public Concept Contribution -> Review -> Merge: [docs/public-contributions.md](docs/public-contributions.md).

Read-only evidence and pinned Citation history from inverse Concept relationships:
[claim-evidence.md](docs/claim-evidence.md).

Private Note/Concept/Claim integrated graph and search, durable five-stream projections and isolated canonical recovery: [brain-network.md](docs/brain-network.md).

Private Learning Roadmap node/edge editing, aggregate revisions and live Outbox/Kafka: [private-roadmaps.md](docs/private-roadmaps.md).

Public Roadmap graph proposals, designated review/merge and immutable public revisions: [public-roadmaps.md](docs/public-roadmaps.md).

Public Roadmap version-pinned ratings, private rating audit and deterministic top100 ranking: [roadmap-ratings.md](docs/roadmap-ratings.md).

Private node progress and manual study sessions, independent revision history and idempotent recording: [learning-study.md](docs/learning-study.md).

Private Note learning evidence: [workflow, API and validation](docs/learning-evidence.md).

Concept learning evidence: [private/public source pins and workflow](docs/concept-learning-evidence.md).

File learning evidence: [sealed file pins, private audit and verified downloads](docs/media-learning-evidence.md).

Optional study-record links for Note/Concept/file evidence: [workflow and independent association audit](docs/evidence-study-sessions.md).

Private Learning activity timeline, ClickHouse projection and retained-event replay: [docs/learning-activity-timeline.md](docs/learning-activity-timeline.md).

Private Planning goals, manual status/target dates, archived discovery and atomic Outbox: [docs/private-goals.md](docs/private-goals.md).

Private Tasks, manual status/due dates, optional same-Space Goal links and retained audit: [docs/private-tasks.md](docs/private-tasks.md).

Private one-time schedules, explicit timezone/DST resolution, interval agenda and optional Task links: [docs/private-schedules.md](docs/private-schedules.md).

Unified Learning + Planning activity history, pinned Goal/Task/Schedule facts and two-owner recovery: [docs/planning-activity-timeline.md](docs/planning-activity-timeline.md).

Finite private recurring schedules, wall-clock previews, atomic child creation and guarded archival: [private-recurrences.md](docs/private-recurrences.md).


Private Schedule reminder settings and immutable time/source confirmation are now available: [reminder settings](docs/private-reminders.md). Explicit in-app dispatch is now available; see the next link.


Explicit server-side dispatch and persistent private in-app inbox are now available: [in-app notifications](docs/in-app-notifications.md). Settings require an explicit arm; existing settings do not auto-send.

Private Space notification receive/pause settings and immutable missed-delivery history: [notification preferences](docs/notification-preferences.md).

Private web bookmarks, exact URL/memo retention, stable retries and archived history: [private bookmarks](docs/private-bookmarks.md).

### Private questions

Personal questions now retain background, direct answers, manual open/answered status,
independent archival and immutable revisions. Open **개인 질문 열기** on `/graph`;
see [usage/ownership/contracts](docs/private-questions.md) and
[local validation](docs/private-questions-validation.txt).

### Private decisions

Personal decisions now retain context, selected outcome, rationale and considered
alternatives, with manual draft/completed status, archival and immutable revisions.
Open **개인 결정 열기** on `/graph`; see [usage/contracts](docs/private-decisions.md)
and [local validation](docs/private-decisions-validation.txt).

### Record-to-Concept links

Private Bookmarks, Questions and Decisions now have independent ordered Concept
links/audit, with private/public selection and typed reverse record discovery.
See [record Concept workflow/contracts](docs/record-concepts.md) and
[local validation](docs/record-concepts-validation.txt).

### Records in integrated graph and search

Bookmarks, Questions and Decisions now join the private integrated network as typed
nodes, with Concept edges, body search and fresh canonical detail. Existing records
can be recovered without resetting data or checkpoints. See
[record network](docs/record-network.md) and [validation](docs/record-network-validation.txt).

- Private Bookmark/Question/Decision and Concept association timeline: [record activity](docs/record-activity.md).

- Note and Daily Note activity history with pinned journal dates and safe bounded previews: [note activity](docs/note-activity.md).

- Ordered Note-to-Concept association history in the private timeline: [Note Concept activity](docs/note-concept-activity.md).

- Typed Note tag/property history with exact decimal retention: [metadata activity](docs/metadata-activity.md).
- [Wiki Link activity](docs/link-activity.md): immutable resolution/occurrence history and bounded explicit current lookup.
- [Note attachment activity](docs/attachment-activity.md): original file relationship history, sealed metadata and explicit current attachment lookup.

- [공개 로드맵을 개인 학습으로 가져오기](docs/roadmap-adoptions.md)

- [관계 조건(Qualifier)과 고정 이력](docs/claim-qualifiers.md)

- [개념 외부 체계 연결과 근거 이력](docs/external-mappings.md)

- [관계 대상의 문자열·숫자·날짜·참/거짓과 근거 이력](docs/typed-claims.md)

- [관계 생성·수정·폐기의 활동 타임라인](docs/claim-activity.md)

- [관계 버전별 변경 항목 비교](docs/claim-comparison.md)

- [폐기한 관계 다시 찾기와 보존 이력 조회](docs/retired-claims.md)
- [폐기한 개념 보관함과 수정 이력 다시 열기](docs/retired-concepts.md)

- [폐기한 출처 보관함과 인용 근거 다시 열기](docs/retired-sources.md)
- [해제한 출처 연결 보관함과 고정 이력 다시 열기](docs/retired-citations.md)
- [폐기한 관계 조건 보관함과 고정 이력 다시 열기](docs/retired-qualifiers.md)
- [폐기한 별칭·외부 체계 연결 보관함](docs/retired-concept-details.md)

- [개념·출처 버전별 변경 항목 비교](docs/knowledge-revision-comparison.md)

- [이전 노트 내용을 확인·편집하고 새 버전으로 저장](docs/note-restore.md)

- [폐기한 노트·Daily Note 보관함과 보존 이력 조회](docs/retired-notes.md)

- [태그별 활성 노트 탐색과 현재 연결 조회](docs/tag-notes.md)

- [속성별 활성 노트와 현재 값 탐색](docs/property-notes.md)

- [속성값 조건으로 활성 노트 좁혀 보기](docs/property-filter.md)

- [숫자·날짜 속성 범위로 활성 노트 찾기](docs/property-range.md)

- [태그와 속성 조건을 함께 적용해 노트 찾기](docs/property-tag.md)

- [태그·속성 이름 일부로 목록 검색](docs/metadata-name-search.md)

Registered account private Group creation, Space OWNER provisioning and recoverable idempotent completion: [docs/private-groups.md](docs/private-groups.md). Group membership, shared roadmaps, Study, Assignment, Submission and Discussion are detailed below. Course organization and ordered Study curriculum are detailed below.

Group invitation issuance/acceptance, MEMBER/VIEWER roles, exclusion, self-leave and current participation authorization: [docs/group-memberships.md](docs/group-memberships.md).

Group shared roadmap links with explicit public-version preview, current membership checks, owner retirement and audit: [docs/group-roadmaps.md](docs/group-roadmaps.md).

Private Group Study organization, owner lifecycle management, safe creation retry and immutable member-readable audit: [docs/group-studies.md](docs/group-studies.md).

Study reference-roadmap selection, owner replacement/unlink and separate graph ownership: [docs/study-roadmap-bindings.md](docs/study-roadmap-bindings.md).

Group Study assignment definitions, owner lifecycle, creation recovery and member-readable history: [docs/study-assignments.md](docs/study-assignments.md).

Explicit assignment answers, resubmission and author/owner-only immutable history: [docs/assignment-submissions.md](docs/assignment-submissions.md).

Group-shared Study discussions, immutable replies and OWNER lock/reopen/archive: [docs/study-discussions.md](docs/study-discussions.md).

Group Course creation, ordered curriculum, lifecycle and history: [docs/group-courses.md](docs/group-courses.md).

Voluntary Course enrollment/withdrawal, private participant history and OWNER roster: [docs/course-enrollments.md](docs/course-enrollments.md).

Participant-only Course Study learning statuses, completion summary and private history: [docs/course-learning-progress.md](docs/course-learning-progress.md).

Explicit participant consent for owner-only Course completion snapshots, withdrawal and consent epochs: [docs/course-progress-sharing.md](docs/course-progress-sharing.md).

Version-pinned submission OWNER feedback, author/owner-only visibility, corrections and immutable audit: [docs/submission-feedback.md](docs/submission-feedback.md).

- [Private assignment resubmission requests](docs/resubmission-requests.md)

- [Original instructions for submitted attempts](docs/submission-instructions.md)

- [Feedback version retraction/restoration](docs/feedback-retractions.md)

- [OWNER submission review queue](docs/submission-review-queue.md)

Private OWNER grading, no self-grading and immutable submitted-version scores: [docs/submission-grades.md](docs/submission-grades.md).

Immutable grade retraction/restoration with retained scores and audit: [docs/grade-retractions.md](docs/grade-retractions.md).

Draft-only shared evaluation criteria with permanent submission-start freeze: [docs/assignment-rubrics.md](docs/assignment-rubrics.md).

Direct or criterion-summed submission grading with immutable calculation basis: [docs/rubric-grading.md](docs/rubric-grading.md)

Private version-pinned submission attachments: [docs/submission-files.md](docs/submission-files.md).

Public GitHub connections: `docs/github-public.md` (registered private Space, explicit connect/sync, immutable imported commits and private Activity).

GitHub completed sync history: `docs/github-sync-history.md` (private completed receipts, logical-version paging, retained after disconnect).


Deterministic private next-study guidance and explicit sourced-draft proposals: [Learning guidance](docs/learning-guidance.md), [public authoring](docs/public-curation.md), [actual validation](docs/guidance-curation-validation.txt).


Latest original-requirement audit and input-contract corrections: [audit](docs/v1-final-audit.md), [Unicode behavior](docs/unicode-contracts.md), [actual validation](docs/unicode-contracts-validation.txt), [remote decision review](docs/remote-delivery-review.md).
