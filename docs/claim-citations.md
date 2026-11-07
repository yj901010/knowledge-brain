# Private Claim Citations

Current extension (2026-10-20): [typed Claim objects](typed-claims.md) adds scalar
objects and V2 Claim/Citation/Qualifier events, preserving prior history. The
following original-stage evidence remains historical; use the typed-Claim
rollout/recovery guidance for the current deployment.


AGENTS.md and PROJECT.md were reviewed before implementation. ClaimCitation is
the Knowledge-owned relationship between a personal Claim and a Source. This
feature uses the existing Knowledge service/database, Space authority, local
ACID transaction and Outbox/CDC topology. It adds no dependency, external dataset,
service-boundary change or cross-service database access.

Select a personal Concept and one of its Claims, then open that Claim's citation
panel. Choose a Source from the personal Source library and explicitly attach
its current revision to the current Claim revision. Each citation displays the
pinned relation and complete source metadata, including their revision IDs and
versions. Editing or retiring a Source later does not silently change previously
cited metadata. Editing a Claim later likewise leaves the pinned relation intact.
Use an explicit inspection and re-pin operation to cite the current versions.

Citation is evidence bookkeeping, not verification. Adding a reference does not
promote the Claim from DRAFT, attest source reliability, infer redistribution rights or publish private information. Source URL opening
remains explicit browser navigation with safe HTTP(S), noopener and noreferrer.
No reference content is fetched, imported, parsed or copied by Knowledge.

## Identity, snapshots and lifecycle

Every ClaimCitation has an immutable UUIDv7 citationId, owning Space, subject and
Claim identity. Visibility is PRIVATE, state is ACTIVE or RETIRED, and positive
version is protected by an independent strong `"citeN"` ETag. Its Claim snapshot
pins claimRevisionId, Claim version, subject, predicate and typed object. Its
Source snapshot pins sourceId, sourceRevisionId, Source version, title, nullable
URL, citation and license note. The existing CONCEPT object type is supported.

Create and update/re-pin require a current ACTIVE PRIVATE Claim with all three
current Concepts ACTIVE PRIVATE and a current ACTIVE PRIVATE Source, all within
the same Space. The exact current Claim and Source versions are required in the
request. Arbitrary historical pin selection is not supported. One ACTIVE citation
per `(space_id, claim_id, source_id)` is allowed; there is no citation count cap.
Different Sources with duplicate titles remain distinct through their IDs.

Citation updates increment only the Citation version. Claim, Source, Concept,
Alias and Content versions/revisions/events remain unchanged. Even a re-pin to
unchanged current reference revisions is an explicit new Citation revision.
GET/list responses contain pinned snapshots only: current Claim/Source status
is fetched separately, so a cite ETag consistently identifies its immutable
representation.

Source retirement does not hide an ACTIVE citation's pinned metadata. Logical
unlinking requires an effective ACTIVE Claim but permits its previously pinned
Source to be RETIRED. It preserves the pins and writes a RETIRED citation revision.
Re-linking that Source, when ACTIVE, creates a new citation ID. Citation and
Claim/Concept retirement remove active citation reads, while a known authorized
citation's history remains readable after Citation, Claim, Concept or Source
retirement. No cascading or destructive deletion is introduced.

## API and authority

Base path:
`/api/spaces/{spaceId}/concepts/{subjectConceptId}/claims/{claimId}/citations`.

| Operation | Result |
| --- | --- |
| GET base, `after`/`limit` | `{items: Citation[], nextAfter: UUIDv7 or null}`; default 20, maximum 100 |
| POST base, `{sourceId, sourceVersion, claimVersion}` | 201, Location and independent `"cite1"` ETag |
| GET `/{citationId}` | ACTIVE PRIVATE Citation with pinned snapshots and `"citeN"` |
| PUT `/{citationId}`, same body, If-Match | Explicitly replace/re-pin reference; increment only Citation version |
| DELETE `/{citationId}`, If-Match | Logical unlink/retirement, 204 and incremented Citation ETag |
| GET `/{citationId}/revisions`, `before`/`limit` | Retained snapshots descending by Citation version; default 5, maximum 10 |

UUIDs and UUID cursors are canonical lowercase UUIDv7s. Collection `after` and
revision `before` are exclusive. `before` defaults to Long.MAX_VALUE. Only known,
single-valued query keys and canonical positive numbers are accepted. Item reads
and writes accept no query parameters. POST/PUT accept application/json, at most
4096 body bytes and exactly sourceId, sourceVersion and claimVersion. Versions
must be positive integer tokens within signed Long.MAX_VALUE; quoted, decimal,
exponent, zero, negative or overflowed versions are invalid. Unknown/duplicate
keys and trailing tokens are invalid. DELETE has no request body; nonempty bodies return 400.

Missing If-Match returns 428. Weak, wildcard, multiple, other-aggregate or
overflowed ETags return 400. Stale Citation, requested current Claim or requested
current Source versions return 412 without a partial mutation. An existing ACTIVE
Claim/Source citation or Citation version overflow returns 409. Unknown, foreign,
public or unavailable required objects return 404. Body overflow is 413 and an
unsupported content type is 415.

Each request uses fresh Space and Identity authority for the common guest or
registered principal. Reads use CONCEPT_READ and mutations use CONCEPT_WRITE in
the owning PERSONAL PRIVATE Space. HTTPS is mandatory, writes require the exact
configured browser Origin, and Sec-Fetch-Site when present must be same-origin.
Private responses use no-store. Actor and trace derive from the verified request,
never from user-supplied body fields. Gateway exposes only the documented private
collection, item and history routes with bounded JSON and queries. OpenAPI:
[claim-citations.yaml](../contracts/openapi/claim-citations.yaml).

## UI behavior

The citation editor is independent beneath the selected Claim. Stable keys use
Space, subject and Claim IDs, not a mutable Claim version or editor mode. Citation
actions preserve sibling Note, Concept, Alias, Claim and Source library drafts.
Changing the selected Claim/Space closes that citation scope. No private state
is stored in browser storage.

Source selection uses actual 20-row UUIDv7 cursor pages, stable IDs and the current
selected Source version. The current Claim version is fetched separately before
pinning. Pinned source/relation metadata and latest metadata are shown separately:
an inspection never overwrites a pin or selection. After 412 the submitted
selection is retained but further writes remain blocked until explicit latest
inspection and restart. Unlink confirmation must likewise be renewed after a
stale Citation ETag. Failed or uncertain mutations are not automatically repeated.

Known selected history survives lifecycle retirement. A retired parent disables
mutations and the active Source catalog while retaining known citation history.
Independent request epochs, selected IDs, Scope checks and 8-second aborts discard
late responses after a view change. Aborting HTTP cannot undo an already committed
transaction. Unavailable authority clears private data; 503 clears stale active
rows and offers retry. Metadata is escaped text; explicit safe URL navigation
does not cause automatic network fetching.

## Persistence, concurrency and events

Knowledge Flyway V6 adds `claim_citation` and `citation_revision`. Composite
same-service foreign keys retain the owning Space/subject/Claim, owning
Space/Source and matching immutable Claim/Source revisions. Existing Claim and
Source revision identities receive composite unique constraints so a pin cannot
reference another aggregate's revision. PRIVATE visibility, ACTIVE/RETIRED
lifecycle and positive versions are checked by PostgreSQL. Partial indexes
enforce one ACTIVE Claim/Source citation and support exclusive ID cursor pages.
Revision IDs and `(citation_id, version)` are unique. Snapshot text is read from
retained immutable Claim/Source revisions; it is not copied into new SQL columns.

Mutation lock order is all currently referenced Concept IDs sorted, then Claim,
then the union of previous and proposed Source IDs sorted, then Citation. Initial
Claim/Citation snapshots are rechecked after locking their canonical rows. A
changed reference set fails with 412 even if a caller guesses a future expected
version, so a mutation cannot proceed using an incomplete lock set. New targets
must match current requested versions and matching ACTIVE revision snapshots.
Retirement may retain a RETIRED prior Source. Source edits lock their own Source
row; Claim edits use the same Concept-then-Claim order.

Every successful create/update/retire commits its canonical Citation, immutable
Citation revision and transactional Outbox event atomically. Failed validation,
stale versions, duplicate active references and rollback leave no partial record
or event. No referenced aggregate is mutated by Citation actions.

`ClaimCitationCreated`, `ClaimCitationUpdated` and `ClaimCitationRetired` travel
through the existing Knowledge Debezium connector to `knowledge.citation.v1`,
keyed by citationId. Outbox aggregatetype is `citation`, aggregateType is
ClaimCitation, producer is knowledge-service, and schema is
`knowledge/citation-events/1`. Its independent immutable Registry artifact uses
VALIDITY FULL and COMPATIBILITY FULL_TRANSITIVE. Existing Concept, Alias, Claim
and Source v1 contract bytes remain unchanged.

The standard 11-field envelope includes stable eventId, eventType/version,
occurrence time, producer, aggregateType/ID, Space, actor, trace and payload.
ClaimCitationChangedPayloadV1 carries citationId, claimId, subjectConceptId,
claimRevisionId/version, predicateConceptId, objectType/objectConceptId,
sourceId, sourceRevisionId/version, full pinned source metadata, Citation
revisionId/version, state and visibility. Empty Protobuf URL denotes canonical
null. Consumers deduplicate eventId, order by citationId/version and enforce
Space authority before displaying private metadata.

The topic uses configured event partitions, replication and seven-day delete
retention. Kafka Connect has literal-topic Write/Describe/DescribeConfigs ACLs;
the Knowledge validator has literal-topic Read/Describe. Metrics are
`brain.knowledge.citation.changes{type=created|updated|retired}` and
`brain.knowledge.citation.reads{type=list|get|history}`, alongside existing
Gateway metrics, database readiness and trace propagation.

## Local validation

Apply brain-connect with local values and wait for the Citation topic and Kafka
users Ready. Run `scripts/Run-CitationContractValidation.py` and inspect its
completed Job for immutable schema and rule checks. Build/import Knowledge and
Gateway with the existing Kubernetes helpers, wait for every printed node import
Job, then run `scripts/Install-LocalClaimCitations.py --helm PATH` to roll out the matching local images. Existing service ownership and
canonical deployment architecture are retained.

Run the complete reactor with `scripts/Run-BackendValidation.py --knowledge-kafka`.
The isolated feature runner is `--knowledge --knowledge-kafka --citations-only`;
its real PostgreSQL and Kafka checks run with Java 25.

Backend checks exercise strict bounded JSON and version/ETag/query contracts,
Space isolation, pinned revision fidelity, effective lifecycle filtering,
retained history, active duplicate enforcement, independent aggregate versions,
atomic rollback, editor/reference races and complete CDC/Kafka transport.
`scripts/Run-GraphWebValidation.py --public` runs the full browser suite;
`--citations-only` runs this feature independently.
Browser checks use actual Claim/Source pages, current revision pinning, explicit
re-pin/conflict recovery, unlink confirmation, retained history and sibling
drafts, unavailable authority/retry, stale responses and mobile layout. Actual
delivery evidence is recorded in
[claim-citations-validation.txt](claim-citations-validation.txt).

Reverse browsing from a selected private Source, with immutable Citation pins and
retained history: [source-citations.md](source-citations.md).

Read-only evidence and pinned Citation history from inverse Concept relationships:
[claim-evidence.md](claim-evidence.md).

Rediscover explicitly unlinked Citations after reload, including retired parents:
[retired-citations.md](retired-citations.md).
