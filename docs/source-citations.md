# Personal Source Citation Discovery

Current extension (2026-10-20): [typed Claim objects](typed-claims.md) adds scalar
objects and V2 Claim/Citation/Qualifier events, preserving prior history. The
following original-stage evidence remains historical; use the typed-Claim
rollout/recovery guidance for the current deployment.


AGENTS.md and PROJECT.md were reviewed before implementation. Source citation
discovery is a read-only view owned by the existing Knowledge service. It uses
Knowledge's canonical Source, Claim, Concept and Citation rows with fresh Space
authority. No dependency, external dataset, service boundary, cross-service SQL,
canonical mutation or new event contract is introduced.

Open the personal Source library, select a Source and open **이 출처를 인용한 관계**.
The panel lists the active effective relations citing that Source across the
current Personal Space. Select a row to inspect the existing Citation's complete
pinned Claim and Source snapshots and retained Citation revision history inline.
The user can browse this evidence without changing the Graph's Concept selection.

## Current discovery and immutable pins

Each summary retains the existing citationId, owning Space and subject, claimId,
Citation version, timestamps and exact pinned Claim revision. Its compact Source
pin contains sourceId, revisionId and Source version. Source title, URL,
bibliographic text and license note remain available in existing Citation detail
and history; the discovery collection does not copy those large text fields.

`subjectTitle` is the **current** owning subject Concept title.
`currentClaimVersion` is the **current** canonical Claim version. Both are
discovery metadata and are labelled separately from `claim.version` and
`source.version`, which identify immutable revisions pinned by the Citation.
Changing a Concept title, Claim or Source does not rewrite a prior Citation pin.
Current predicate/object titles must not be presented as titles of pinned IDs.
Duplicate titles remain distinguishable by stable subject, Claim and Citation IDs.

The collection emits no ETag because current discovery fields can change
independently of a Citation's own version. Existing nested Citation detail retains
its independent strong `"citeN"` ETag and returns pinned snapshots only. Discovery
does not re-pin evidence or promote a Claim's verification state.

## Authority, pagination and lifecycle

The only new route is:

`GET /api/spaces/{spaceId}/sources/{sourceId}/citations?after={citationId}&limit=20`

The response is `{items: SourceCitation[], nextAfter: UUIDv7 or null}`. Rows sort
by ascending citationId. `after` is exclusive; it may refer to an ID that is no
longer returned or never existed. The default page size is 20 and the maximum is
100. UUIDs and UUID cursors must be canonical lowercase UUIDv7s. Only singleton
`after` and `limit` query keys are accepted. `limit` is a canonical positive
decimal; leading zeros, signs, whitespace, decimals, exponents, overflow and
values outside 1 through 100 return 400. A nonempty GET body also returns 400.

Every request requires HTTPS and fresh CONCEPT_READ authority from the common
guest/registered principal for the owning PERSONAL PRIVATE Space. Unknown or
foreign Sources, public/non-Personal Spaces and Sources outside the private scope
return 404. Missing or revoked identity returns 401; denied permission returns
403. Persistence or authority outages fail closed with 503. Private responses
use `Cache-Control: no-store`. OpenAPI:
[source-citations.yaml](../contracts/openapi/source-citations.yaml).

A known PRIVATE Source is discoverable by retained identity in either ACTIVE or
RETIRED state. An empty known Source returns 200 with an empty page. Source
retirement alone does not remove its active Citations or pinned evidence.
Discovery includes only ACTIVE PRIVATE Citations whose **current** Claim and all
three current subject/predicate/object Concepts are ACTIVE PRIVATE in the same
Space. Citation unlinking, Claim retirement or referenced Concept retirement
removes the affected row from active discovery.

An already selected authorized Citation's known history remains readable after
Citation, Claim, Concept or Source retirement through the existing route:

`GET /api/spaces/{spaceId}/concepts/{subjectConceptId}/claims/{claimId}/citations/{citationId}/revisions`

That retained history sorts by descending Citation version, defaults to five
rows, accepts a maximum of ten and uses the exclusive positive `before` cursor.
Retained identifiers from selection allow history browsing even when a refreshed
discovery list becomes empty or the active Citation item route returns 404.
The existing [Claim Citation contract](claim-citations.md) governs full pinned
detail, history and mutations.

## UI behavior

The Source citation panel is an independent child keyed only by Space and Source
identity. Source edit mode or version changes preserve its selected Citation and
known history. Browsing Citation detail/history preserves Source, Concept, Alias,
Claim and Note drafts. Switching Source or Space closes that read scope. Source
retirement preserves active discovery and known Citation history.

The panel uses real 20-row cursor pages. Current subject titles and current Claim
versions are distinguished from pinned versions; stable IDs identify duplicate
titles. Detail and history are explicit reads of existing endpoints and do not
cause automatic re-pinning, editing, navigation or content import. Source URLs
remain escaped metadata and require explicit safe HTTP(S) navigation with
noopener/noreferrer. No external URL is fetched automatically and no private
state is written to browser storage.

Each request has its own epoch, selected IDs, scope checks and an eight-second
abort. Late responses after selection, close or Space changes are discarded.
A list 503 clears stale rows and offers retry. Detail/history errors clear their
respective stale views. Identity/permission denial or a missing scoped Source
clears private data and invokes the existing denied-access flow. Known retained
history is kept separate from active discovery so lifecycle changes do not make
an old snapshot appear to be currently active.

## Persistence and observability

Knowledge Flyway V7 adds only:

`claim_citation_source_active_idx ON claim_citation(space_id, source_id, citation_id) WHERE state='ACTIVE'`

Existing migrations, tables, revision identities, constraints, events, schemas,
topics and ACLs retain their contracts. The partial index supports the Source
identity predicate and exclusive citation cursor. It does not alter canonical
lifecycle rules or introduce a new aggregate.

The store first validates the retained scoped PRIVATE Source identity regardless
of lifecycle. A single SQL snapshot then rechecks that Source identity and joins
canonical Citation/Claim/current Concepts with the pinned Claim and Source
revisions. Lifecycle, Space and visibility predicates apply in this same query.
All joins stay within the Knowledge-owned database. Reads create no canonical,
revision or Outbox row and increment no domain version.

Successful discovery increments
`brain.knowledge.citation.reads{type=source}`. Existing Gateway metrics, Knowledge
database readiness and HTTP trace propagation continue to cover this route.
The production Sources security matcher already protects the nested collection;
Gateway exposes exactly its private GET route with shared Source cursor limits.

## Validation

Use the existing Kubernetes build/import/deploy flow for Knowledge and Gateway.
Wait for matching per-node imports and both application rollouts, then verify
Flyway V7 is applied. The complete backend validation command remains
`scripts/Run-BackendValidation.py --knowledge-kafka`; full browser validation is
`scripts/Run-GraphWebValidation.py --public`.

Applicable checks cover real production servlet filters, HTTPS and fresh Space
authority, strict IDs/body/cursors, retired Source discovery, effective current
Claim/Concept lifecycle filtering, pinned snapshot fidelity, cross-Space denial,
exclusive pages and the actual PostgreSQL index. GET independence is checked
against canonical versions, revision counts and Outbox rows. Existing event
contract bytes remain unchanged.

Browser validation covers real 20-plus-two cursor pages, duplicate subject
titles, Claim/Source edits with unchanged pins, Citation unlinking, Source
retirement and retained history after Claim/Concept retirement. It also checks
stale responses, close/scope changes, 503 retry, authority revocation, sibling
draft preservation, safe metadata display, no external fetch/storage and a
390-pixel mobile viewport. Record actual delivery results in
[source-citations-validation.txt](source-citations-validation.txt).

Retired Sources can now be rediscovered after closing or reloading the library;
see [retired Source archive](retired-sources.md).
