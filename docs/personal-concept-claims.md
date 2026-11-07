# Personal Concept Claims

Current discovery extension: [retired Claim catalogue](retired-claims.md) adds
explicit read-only `state=RETIRED` pages without changing active read/write rules.

Current extension (2026-10-20): [typed Claim objects](typed-claims.md) adds scalar
objects and V2 Claim/Citation/Qualifier events, preserving prior history. The
following original-stage evidence remains historical; use the typed-Claim
rollout/recovery guidance for the current deployment.


AGENTS.md and PROJECT.md were reviewed before implementation. Claim and its
immutable revisions remain Knowledge-owned, using the current Space
CONCEPT_READ/CONCEPT_WRITE authority for the common guest/registered principal.
This adds the planned Personal Brain Subject -> Predicate -> Object model within
the existing service boundaries, local transaction and Outbox/CDC architecture.
There is no new dependency or external terminology dataset.

Open **개인 개념 보관함**, select a Concept and use **개념 관계 (Claim)** below its
editor. The selected Concept is the fixed subject. Load actual 20-row private
Concept cursor pages in **관계에서 사용할 개인 개념**, then select **새 관계의 서술
개념** and **새 관계의 대상 개념**, and use **관계 추가**. For example, three private
Concepts can express `Java -> supports -> virtual threads`. Predicate and object
are Knowledge Concept IDs, including when titles happen to be equal. This
records the user's private draft relationship and does not verify the statement.

The Claim catalogue also uses actual 20-row UUIDv7 pages. **개념 관계 더 보기** and
**관계 개념 더 보기** use independent exclusive cursors; displayed counts describe
only loaded rows. Select **관계 열기** to read, edit, retire or inspect its immutable
revision history. Claim, Alias, Concept and Note editors have independent state,
so Claim operations do not reset a Concept or Note draft. Titles shown in a
relationship come from currently loaded Concept rows; immutable IDs remain
visible and serve as a fallback when a title is not loaded. Claim history stores
the referenced Concept identities rather than duplicating their title histories.

## Scope and lifecycle

Every Claim has immutable UUIDv7 claimId, Space and subject Concept IDs, an
independent positive version and strong `"clN"` ETag. Visibility is PRIVATE and
verificationState is DRAFT. The initial object type is `CONCEPT`, with one
immutable Concept ID. Subject, predicate and object must each be ACTIVE PRIVATE
Concepts in the same authorized PERSONAL Space. Positions can reference the same
Concept; a relationship can also use another Concept as its subject. Predicate
and object can be changed explicitly while subject and Claim identity stay fixed.

An exact `(space, subject, predicate, object)` triple is unique among ACTIVE
Claims. Different subjects or objects can produce distinct Claims. There is no
active-Claim count cap. Logical retirement retains the Claim and revisions;
recreating a retired triple generates a new UUIDv7 and its own version 1 history.

Retiring any referenced Concept removes the relationship from effective active
reads. It does not cascade delete Claim data, increment the Claim version or
synthesize a ClaimRetired event. All current references, plus proposed predicate
and object on update, must still be ACTIVE PRIVATE for any Claim write, including
explicit Claim retirement. A hidden Claim cannot be edited back into visibility
after one of its current references retires. Known authorized Claim history stays
readable through its Space/subject/Claim path even after Claim or Concept
retirement. The UI preserves a selected relationship's history after explicit
subject retirement; a catalogue for discovering all hidden or retired Claims and
restoring them is a future feature.

Claim-to-Source citations with pinned revisions are implemented in
[claim-citations.md](claim-citations.md).

Literal object values, verification transitions,
and public Claim contribution/review are separate later scopes. Private Concept
external mapping candidates are implemented separately in [external-mappings.md](external-mappings.md).
This API provides canonical private reads; Claim graph/search projections are
also future work and current Note Graph behavior is unchanged.

## API

The base path is `/api/spaces/{spaceId}/concepts/{subjectConceptId}/claims`.

| Operation | Result |
| --- | --- |
| GET base, `after`/`limit` | `{items: Claim[], nextAfter: UUIDv7 or null}`; default 20, maximum 100 |
| POST base, `{predicateConceptId, object: {type: "CONCEPT", conceptId}}` | 201, Location and independent `"cl1"` ETag |
| GET `/{claimId}` | Effective ACTIVE Claim with `"clN"` ETag |
| PUT `/{claimId}`, same strict body, If-Match | Updated predicate/object and incremented independent Claim ETag |
| DELETE `/{claimId}`, If-Match | Logical Claim retirement, 204 and incremented Claim ETag |
| GET `/{claimId}/revisions`, `before`/`limit` | Retained immutable snapshots in descending version order; default 5, maximum 10 |

Canonical lowercase UUIDv7s identify every object and UUID cursor. Query keys
must be known and single-valued. `after` and revision `before` are exclusive;
`before` defaults to Long.MAX_VALUE. POST/PUT require application/json, at most
4096 body bytes, exactly the documented outer and object keys, and the exact
`CONCEPT` discriminator. Duplicate keys, trailing tokens, unknown fields,
noncanonical IDs and unsupported object types return 400. Larger bodies return
413 and unsupported content types return 415.

Missing If-Match returns 428; weak, wildcard, multiple or another aggregate's
ETag returns 400. A stale version or a Claim changed between initial read and
locking returns 412. Duplicate active triple returns 409; a version that cannot
be incremented beyond Long.MAX_VALUE also returns 409 on update or retirement.
Unknown, foreign, public, mismatched or unavailable active references return 404.
Active reads omit Claims with retired predicate/object references; an unavailable
subject returns 404. History requires the known authorized Space/subject/Claim
identity rather than discovering foreign records.

Every operation requires fresh Space and Identity authorization. Writes require
HTTPS and the configured exact browser Origin; Sec-Fetch-Site must be same-origin
when present. Authority failures stop before canonical lookup or mutation.
Responses use no-store. Gateway allows only the exact canonical private Claim
routes, bounded collection/history queries and 4096-byte bodies. OpenAPI:
[personal-concept-claims.yaml](../contracts/openapi/personal-concept-claims.yaml).

The UI retains predicate/object selections after validation, duplicate, failure
and conflict responses. It does not automatically repeat a POST or uncertain
write. **최신 관계 확인** displays the latest relationship without overwriting
unsaved selections; **최신 관계 버전으로 다시 시작** explicitly resets the draft to
that version. Retirement requires acknowledgement of the displayed revision.
After conflict, **최신 관계 버전으로 폐기 다시 확인** starts a fresh confirmation.
Closing detail or changing the selected Claim, Concept or Space discards an
unsaved Claim selection, as the form states. Catalogue/picker failures clear rows
for explicit retry, and 401/403/404 clear private parent state. Request generations,
selected-ID checks, AbortController and eight-second bounds discard responses
from a closed/switched view. No localStorage/sessionStorage is used. Aborting a
browser request cannot undo a transaction already committed by Knowledge.

## Persistence and events

Knowledge Flyway V4 adds `claim` and `claim_revision`. Three composite
`(space_id, concept_id)` foreign keys enforce same Space references within the
Knowledge database. DB checks retain PRIVATE visibility, CONCEPT object type,
DRAFT verification and positive versions. The partial active-triple unique index
prevents duplicate live relationships; the active subject/Claim-ID index supports
cursor reads. Revision identities and `(claim_id, version)` are unique.

Each write locks the union of all current and proposed referenced Concept rows
in PostgreSQL Concept-ID order, then locks the Claim. Duplicate positions are
locked once. The same Concept locks serialize against Concept retirement and
against competing duplicate writes, including reverse relationships. Update and
retirement recheck the Claim's locked version against both the initial snapshot
and If-Match before changing it. Successful create/update/retire atomically commit
the Claim, immutable snapshot and transactional Outbox. Failed or stale writes
leave no partial revision/event. Concept title, description, version, revisions,
Alias state and Content reference versions remain independent.

`ClaimCreated`, `ClaimUpdated` and `ClaimRetired` carry complete Protobuf revision
snapshots through the existing Debezium Knowledge connector to
`knowledge.claim.v1`, keyed by claimId. The independent immutable artifact
`knowledge/claim-events` version 1 uses VALIDITY FULL and COMPATIBILITY
FULL_TRANSITIVE. Existing Concept and Alias published contracts remain unchanged.
The envelope carries stable eventId, event type/version, producer, aggregate,
Space/actor/trace IDs and occurrence time; the payload contains the immutable
triple, revision ID, version, lifecycle, PRIVATE visibility and DRAFT state.
Consumers deduplicate eventId and order by claimId/version. They must combine the
three referenced Concept lifecycle states and authorization before displaying
active results. Kafka Connect has explicit Write permission and the Knowledge
validator explicit Read permission for this literal topic.

Metrics: `brain.knowledge.claim.changes{type=created|updated|retired}` and
`brain.knowledge.claim.reads{type=list|get|history}`, plus existing Gateway
metrics, database readiness and trace propagation.

## Local delivery and validation

Apply the brain-connect chart with local values and wait for the Claim topic and
Kafka users Ready. Run `scripts/Run-ClaimContractValidation.py`; inspect the
Complete Job and its immutable-schema/rule checks. Build/import Knowledge and
Gateway with the existing local helpers, wait for every printed per-node import
Job, then run `scripts/Install-LocalPersonalConceptClaims.py --helm PATH`. This
keeps the existing Kubernetes/service/database/Outbox topology.

`scripts/Run-BackendValidation.py --knowledge-kafka` runs the full reactor with
real PostgreSQL and Knowledge CDC/Kafka validation. The Claim API/store checks
exercise strict requests, own ETags, private Space/reference isolation,
cursor/history boundaries, active-triple uniqueness, revisions, atomic rollback,
concurrent editors, reverse relationships, retirement races and independent
Concept/Alias state. The existing Knowledge CDC path validates schema/topic/key
and complete Claim event snapshots.

`scripts/Run-GraphWebValidation.py --concept-claims-only` runs the focused actual
API/browser scenario; the full `--public` suite includes it. Browser checks cover
Concept and Claim paging, explicit create/edit/retire/history, retained drafts,
duplicate/stale requests, reference retirement, late responses, failures/retry,
revocation and desktop/mobile layout. Validation evidence is recorded in
[personal-concept-claims-validation.txt](personal-concept-claims-validation.txt).

Private Concept object/predicate usage browsing with independent known Claim history:
[claim-usages.md](claim-usages.md).


Private typed conditions and pinned Claim revision history are implemented:
[claim-qualifiers.md](claim-qualifiers.md).
