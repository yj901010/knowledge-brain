# Personal Concept Claim usages

Current extension (2026-10-20): [typed Claim objects](typed-claims.md) adds scalar
objects and V2 Claim/Citation/Qualifier events, preserving prior history. The
following original-stage evidence remains historical; use the typed-Claim
rollout/recovery guidance for the current deployment.


AGENTS.md and PROJECT.md were reviewed before implementation. This adds
Knowledge-owned inverse discovery over existing private Concept/Claim data.
Service ownership, the Subject -> Predicate -> Object model, common principal,
Space permissions, canonical revisions and immutable event contracts remain
compatible. No dependency, external dataset or cross-service SQL is introduced.

Open **개인 개념 보관함**, select a Concept and use **이 개념을 사용하는 관계**.
**대상으로 사용한 관계** lists current Claims whose object is this Concept.
**서술로 사용한 관계** lists Claims whose predicate is this Concept. For example,
selecting `virtual threads` discovers `Java -> supports -> virtual threads`;
selecting `supports` in the predicate role discovers uses of that relation.

Each role has real 20-row exclusive Claim-ID pages. Rows show the current three
Concept titles, independent Claim version, DRAFT state and stable subject/Claim
IDs, including when titles are equal. A Claim using this Concept in multiple
positions appears once in each applicable role. There is no combined total
count; displayed counts describe loaded rows only.

Select **사용 관계 열기** to read the existing canonical Claim detail and
**사용 관계 수정 이력** to inspect immutable revisions, five at a time with an
exclusive before cursor. Detail/history are independent reads and preserve
unsaved Concept, Alias, outgoing Claim and Note drafts. The panel does not
switch the parent Concept or write a relationship.

Titles are current metadata from the same SQL snapshot as the discovered Claim.
They may change independently of Claim version. History therefore shows the
stored triple IDs and revision metadata, without attaching current titles to
older triples. Claim-to-Source pins remain governed by [claim-citations.md](claim-citations.md).
GET usage/detail/history creates no canonical version, revision, Citation or
Outbox event and does not promote verification state.

## API and lifecycle

`GET /api/spaces/{spaceId}/concepts/{conceptId}/claim-usages?role=OBJECT&limit=20&after={claimId}`

`role` defaults to OBJECT and accepts exactly OBJECT or PREDICATE. `limit`
defaults to 20 and is 1..100. `after` is an exclusive ascending Claim UUIDv7
cursor and need not identify a live row. Known query keys must be single-valued.
Canonical lowercase UUIDv7s are required in raw paths and cursors. Unknown keys,
duplicate keys, other roles, malformed/noncanonical values and nonempty GET
bodies return 400. The Gateway exposes only this exact private GET collection;
no new item/history/write/public/internal endpoint exists.

The response is `{items: [{claim, subjectTitle, predicateTitle, objectTitle}],
nextAfter}`. It uses no-store and emits no collection ETag. Existing nested
Claim item reads retain their independent strong clN ETag. OpenAPI:
[claim-usages.yaml](../contracts/openapi/claim-usages.yaml).

Every request requires HTTPS and fresh CONCEPT_READ authority through the
existing common guest/registered principal for the owning PERSONAL PRIVATE
Space. Missing/revoked identity returns 401; denied authority returns 403;
unknown/foreign/public Concept identity or non-Personal Space returns 404;
authority/DB outages fail closed with 503. Caller-supplied principal headers do
not replace verified authority.

A known PRIVATE Concept retains its identity after retirement and returns an
empty discovery page. An active Claim is discoverable only while the Claim and
all three current Concepts are ACTIVE PRIVATE in the same Space. Claim edits
immediately change its role membership on the next read. Claim/Concept
retirement hides effective discovery without rewriting past revisions or
Citation pins. An already selected authorized Claim's known history remains
available through its original Space/subject/Claim route, even after retirement.

The panel is keyed only by Space and Concept identity. Parent editor mode or
version changes preserve selected Claim history. Parent retirement refreshes
active discovery without closing selected history. Changing role, Concept or
Space clears the old read scope. Independent request generations, selected-ID
checks and eight-second aborts discard late responses after switch/close.
503 clears stale list/detail/history rows with explicit retry. 401/403 and a
missing scoped Concept clear private parent state. Active Claim item 404 retains
the known history route. Metadata is escaped, no external URL is fetched, and
no private state is saved to browser storage. Mobile controls and long IDs wrap
within a 390-pixel viewport.

## Storage, metrics and delivery

Knowledge Flyway V8 adds only partial ACTIVE indexes on
`claim(space_id, object_concept_id, claim_id)` and
`claim(space_id, predicate_concept_id, claim_id)`. A scoped private identity
lookup permits retained Concepts; the collection query joins current Claim and
its three current Concepts within Knowledge's owned DB, applying all Space,
visibility, lifecycle and role predicates in one statement. No other service's
tables are queried. Earlier migrations and published Protobuf bytes remain
unchanged; no topic, schema, ACL or event version is added.

Successful reads increment `brain.knowledge.claim.reads` with type
`usage-object` or `usage-predicate`. Existing HTTP trace propagation, Gateway
metrics and DB readiness cover the path.

Use the existing Java 25 Kubernetes validation/build/import flow and
`Install-LocalClaimUsages.py` after per-node imports complete. Full backend
validation is `Run-BackendValidation.py --knowledge-kafka`; full browser
validation is `Run-GraphWebValidation.py --public`, with
`--claim-usages-only` available for a focused real-API browser run. Actual
delivery counts, migration, images, desktop/mobile QA and cleanup evidence are
recorded in [claim-usages-validation.txt](claim-usages-validation.txt).

Read-only evidence and pinned Citation history from inverse Concept relationships:
[claim-evidence.md](claim-evidence.md).
