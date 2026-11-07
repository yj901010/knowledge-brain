# Personal Brain notes scope

Accepted functional scope: Markdown Note, Wiki Link, Backlink, Tag, Properties,
Graph View, Daily Note/Journal, Attachment, Search, Note-to-Concept links.
This records planned capabilities; they are not all implemented yet.

1. Common HTTPS Gateway for existing Identity/Space APIs.
2. Content-owned Markdown Note CRUD, UUIDv7, PRIVATE Space authorization,
   revision history, optimistic concurrency, and transactional Outbox events.
3. Content-owned Wiki Links/backlinks: stable note IDs across rename, deterministic
   parsing, unresolved links and same-Space resolution. No AI parser.
4. Space-scoped tags, typed/validated properties and date/timezone-aware Daily Notes.
   Journal text remains Content-owned; Activity owns event/timeline projections.
5. Media-owned upload metadata and MinIO presigned transfers; Content owns note
   attachment relations. File data is not stored in PostgreSQL.
6. Rebuildable OpenSearch/JanusGraph projections from domain events, presented
   through Brain Query with permission filtering, including titles/edges/counts.
7. Explicit Content-owned references to Knowledge concept IDs, without copying
   concepts or allowing private notes to change canonical public knowledge.

Content keeps canonical note/link/tag/property data in its owned PostgreSQL.
No cross-service SQL, joins or foreign keys are introduced. Search/graph/events
must never expose another private Space. New dependencies require license review
before addition. Registered claiming preserves existing Space/note identifiers.

Completed: stages 1 and 2 (Gateway and private Markdown Note CRUD/revisions/Outbox),
validated with real PostgreSQL, Kafka and common-origin Kubernetes HTTPS.
Stage 3 is now implemented: same-Space stable Wiki Links, unresolved/ambiguous
references and private backlinks. Stage 4 is implemented: tags, typed properties,
Daily Notes and the Content Journal.
Stage 5 is implemented: private Media uploads, checksum-verified sealed MinIO
versions, Content-owned attachment relations and revisions.
Search projection and its permission-filtered user API are implemented. Graph storage, live dual-topic Kafka consumption and canonical rebuild are implemented;
the authorized Graph API and Next.js Graph View UI are implemented. Cross-domain Activity timeline
projections and other stages remain planned. Private Note-to-Concept links and Knowledge personal Concept CRUD are now implemented.
See [markdown-notes.md](markdown-notes.md) for current scope and limitations.

See [wiki-links.md](wiki-links.md) for syntax, lifecycle and event semantics.

See [note-metadata.md](note-metadata.md) for Tag/Properties API and limits.

See [daily-notes.md](daily-notes.md) for date/timezone and Journal semantics.

See [note-attachments.md](note-attachments.md) for Media transfer and attachment APIs.

Stage 6 foundation: private OpenSearch is deployed; Brain Query has validated
Note decoding, a strict mapping, Space-filtered query construction and a tested mTLS
adapter. Kafka consumption with durable Inbox/queue and outage recovery is now
implemented. Canonical snapshot rebuild with concurrent Kafka replay is implemented.
The end-user search API is deployed with fresh Space permission checks; graph storage, live consumer and canonical rebuild are implemented; the authorized Graph API/UI are deployed;
see [note-search.md](note-search.md).

Graph ownership, TLS/roles and independent Note/link revision ordering: [note-graph.md](note-graph.md).

Stage 6 user graph delivery and validation: [note-graph-api.md](note-graph-api.md).

Stage 7 personal Concept references: [note-concepts.md](note-concepts.md). Public references and Knowledge contribution/review are now implemented; see the following delivery documents.

Registered Keycloak login, guest Space claiming and multiple private Space selection: [registered-login.md](registered-login.md).

Public Concept catalog and registered Contribution -> Review -> Merge, with
operator-designated reviewers: [public-contributions.md](public-contributions.md).
Public Note-to-Concept selection: [public-concept-references.md](public-concept-references.md).

The current UI prioritizes functional validation. The agreed later graph redesign
uses a dark canvas, relationship-driven clustering, selected-neighbor emphasis,
type/tag colors, degree-based node sizes, zoom-sensitive labels and exploration
panels. Rendering performance and interaction/layout work belong to that phase.

Graph inspector Note editing, strong ETag conflict handling and canonical revision
history UI: [note-editor.md](note-editor.md).

Private Note title/body search UI: [note-search-ui.md](note-search-ui.md).
Canonical Wiki Link statuses and grouped Backlink navigation UI:
[note-links-ui.md](note-links-ui.md).

Tag and typed property editing UI: [note-metadata-ui.md](note-metadata-ui.md).

Daily Note / Journal UI: [note-journal-ui.md](note-journal-ui.md).
Private attachment upload, reuse, download and history UI: [note-attachments-ui.md](note-attachments-ui.md).

Canonical full Note collection browsing and selection outside the graph window:
[note-library-ui.md](note-library-ui.md).

Source-preserving Markdown preview for canonical body, unsaved draft and history:
[note-markdown-preview.md](note-markdown-preview.md).

Explicit version-protected Note retirement UI: [note-retirement-ui.md](note-retirement-ui.md).

Paged personal Concept selection, explicit reference conflict recovery and canonical link history UI: [note-concepts-ui.md](note-concepts-ui.md).

Personal Concept descriptions, explicit edit conflict recovery and canonical Concept revision history UI: [personal-concept-editor.md](personal-concept-editor.md).

Personal Concept retirement with retained revisions and Note references: [personal-concept-retirement.md](personal-concept-retirement.md).

Personal Concept management without selecting a Note: [personal-concept-library.md](personal-concept-library.md).

Concept-to-private-Note reference browsing and canonical Note navigation:
[concept-notes.md](concept-notes.md).

Knowledge Core personal Concept Aliases with independent revisions and private
Space scope: [personal-concept-aliases.md](personal-concept-aliases.md).

Knowledge Core personal Concept-to-Concept Claims with independent revisions,
version conflicts and retained lifecycle history:
[personal-concept-claims.md](personal-concept-claims.md).

Knowledge Core private Source metadata library, independent revisions and
retained retirement history: [personal-sources.md](personal-sources.md).

Knowledge Core Claim-to-Source citations with pinned Claim/Source revisions,
independent lifecycle and explicit version conflict recovery: [claim-citations.md](claim-citations.md).

Read-only private Source-to-Citation browsing, pinned detail and retained history:
[source-citations.md](source-citations.md).

Private Concept object/predicate usage browsing with independent known Claim history:
[claim-usages.md](claim-usages.md).

Read-only evidence and pinned Citation history from inverse Concept relationships:
[claim-evidence.md](claim-evidence.md).

Explicit comparison of pinned evidence with current Source metadata:
[evidence-comparison.md](evidence-comparison.md).

Private Note/Concept/Claim integrated graph and search, durable five-stream projections and isolated canonical recovery: [brain-network.md](brain-network.md).
