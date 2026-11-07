# V1 final requirement audit

Audit date: 2026-11-07. The agreed V1 remains **active**. This audit preserves
`AGENTS.md`, `PROJECT.md` and their approved decisions; it does not redefine V1
around the latest passing package. The final local candidate passed73 browser scenario executions plus original-data
continuity,245 Gateway checks and71 Python checks (one explicit OS skip).
[Validation](unicode-contracts-validation.txt) and receipts under
`.local/v1-final-audit` record their exact scope; they do not complete external work.

The [source reconciliation](v1-source-audit.md) supplies the detailed
requirement/store/controller/test map. This follow-up checks that map against
current source, deployed artifacts and substantive evidence. Historical tests
are identified as historical rather than counted as fresh executions.

| Original requirement family | Authoritative implementation and evidence | Finding |
| --- | --- | --- |
| Markdown/history/CAS, stable-ID Wiki Links/unresolved links, backlinks | Content stores/contracts, Note editor/restore/link suites and source reconciliation | Implemented; current private regression and original-data continuity are checked separately. |
| Space tags, typed properties, graph, dated/timezone Journal, attachments, search, Note-to-Concept links | Content/Knowledge/Media ownership, Query projections, real MinIO/OpenSearch/JanusGraph evidence and current private tests | Implemented. Unicode normalization/native input defects found in this audit are corrected without altering the owning contracts. |
| Questions, Decisions, Bookmarks, private Concepts/Claims, Timeline | Owner stores, immutable revisions, Activity projections and substantive personal suites | Implemented. No automatic public sharing or copied cross-owner data. |
| Goals, Tasks, Schedules, Reminders, Recurrence and notifications | Planning/Notification source, date/timezone/consent/idempotence tests and runtime evidence | Implemented; title edits preserve the server's Java whitespace semantics. |
| Shared Knowledge entities and typed claims, source/evidence, revision/verification/mapping | Generic Knowledge model, typed public workbench, source/reference/version checks and real independent-review regressions | Implemented. Expert verification and official mapping remain separate current capabilities. |
| Public contribution, review and atomic merge | Registered author/current designated reviewer, no self approval, source pins/CAS and immutable audit | Implemented as an explicit workflow. Actual initial content review is still separate work. |
| Public graph/table/search | Canonical checks over event-derived OpenSearch/JanusGraph, typed tables and bounded graph UI | Implemented; cached candidates never replace current canonical authority. |
| Roadmaps/nodes/edges, sessions/progress/evidence, ratings/ranking and rule-based guidance | Learning source/contracts, actual owner-DB checks and saved-graph/manual-progress browser evidence | Implemented. Guidance is deterministic; progress and public adoption remain explicit. |
| Groups, Studies, Courses, Assignments, Discussions and Submissions | Collaboration/Space ownership and real Group browser/database regressions | Implemented, including approved grading/rubric/files/feedback/resubmission/notification/progress-sharing policies. |
| Guest/Keycloak identity, Space claiming and multiple personal Spaces | Common principal, immutable ownership references, fresh authorization and real returning/registered/foreign-Space checks | Implemented. Four Space kinds remain in the model; no new Organization administration UI is inferred. |
| Public GitHub repository/commit/PR/Issue observation and private learning links | Integration source, provenance/history, explicit connection/sync and corresponding runtime evidence | Implemented within the approved public-import scope. Private GitHub App authorization is future scope. |
| Image/PDF/generic files | Media metadata, versioned MinIO objects, quotas, scoped presigned transfers and real byte checks | Implemented. Physical-version GC/quota reclamation remains a documented limitation. |
| 13 MSA, Java25, owner DBs, UUIDv7, REST/OpenAPI and local transactions | Source/POM/contracts/migrations, owner isolation tests and deployed immutable images | Preserved. No architecture merge, cross-owner SQL or distributed transaction is introduced. |
| Outbox→Connect→Kafka, Protobuf/Registry, at-least-once/idempotent projections | Actual source-matching Registry/CDC, replay/storage and failure/recovery evidence | Preserved. One PG-only graph test was incorrectly reported as success when its graph body did not run; its condition is now an explicit skip. |
| Valkey/search/graph/analytics read models | Bounded public candidate cache, fresh authority, actual outage/recovery and private CDC/revocation evidence | Implemented; derived stores remain rebuildable and noncanonical. |
| Observability, representative local load and recoverable backups | 13 JVMs, traces/lag/privacy/fault checks, bounded600-second profile and actual13-volume restore | Delivered within the recorded local scope; no new production certification is inferred. |
| GitHub→Actions→GHCR→reviewed GitOps→Argo CD→Kubernetes | Local CI/image gates/controller/drift/rollback are real; hosted repository/runs/packages/adoption are not yet performed | **Open: concrete external source/license/hosting choices and actual hosted execution/adoption.** |
| Initial five medical domains plus Backend/CS factual content and rights |21 linked drafts, eight primary sources, scoped rights notes and explicit ID/version authoring/proposal workflow | **Open: actual semantic/rights inspection, designated independent review and canonical publication.** Draft previews and synthetic Development tests do not establish clinical or legal verification. |

## Current source continuity

The machine-readable source audit compares750 Java/SQL/POM files across ten
unchanged owner services with the earlier source snapshot. All match, with no
new files in that scope. The retained Learning208-file source receipt still
matches. The new Gateway source/artifact receipt covers435 files. Brain Query's
106 current compiled application classes match its deployed Valkey image archive;
the query change in this audit is test-only. These comparisons do not imply that
every old test was rerun on the latest Gateway.

## Corrections and evidence boundaries

- Native HTML length uses UTF-16 units; existing owner contracts use Unicode
  code points for selected natural-language fields. Native ceilings and submit
  guards now agree on the semantic limit. ASCII/UUID/URL/date/key and UTF-8 body
  limits remain distinct, including the existing12,000-byte public fact content.
- Shared Java whitespace rules preserve NBSP, narrow NBSP and BOM in metadata
  names and filenames. Existing metadata replacement still appends its ordinary
  revision; no equal-content no-op is invented.
- `GraphProjectionInboxIntegrationTest` keeps its PG class gate and now marks
  the graph-dependent method with its Graph environment condition. A new isolated
  PG run executes two tests and skips that one correctly. The historical
  `runtime-upgrade/graph-live.json`/log independently records the real Cassandra
  path with all three methods executed; the new PG-only run is not that proof.
- Older framework totals must not be interpreted as fresh, substantive execution
  of every external integration. Likewise, a green Job or running connector is
  not alone proof of every feature or new CDC tail.

## Decisions and remaining execution

The [remote delivery review](remote-delivery-review.md) prepares the repository,
license, plan/protection, Actions and GHCR choices. A local source archive with
its complete path/size/SHA manifest must precede external publication approval.
That approval does not confer medical credentials, clear all source rights or
automatically merge public knowledge. Existing-app adoption requires its rendered
diff and operational authority to be concretely reviewed.

The21 supplied factual drafts stay unresolved, unpublished and independently
unreviewed until their actual review workflow is completed. A project-code
license is not a medical-data license. Future AI, native mobile, audio, private
GitHub authorization and site-level production HA are not silently added to V1.
Off-host destinations, retention/scheduling, SLOs and any production PITR require
their actual applicable decisions; the verified single-host restore/load scope
does not establish them.
