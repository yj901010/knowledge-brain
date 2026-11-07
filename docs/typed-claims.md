# Typed private Claim objects

Current extension (2026-10-21): [Claim activity](claim-activity.md) preserves
original V1/V2 Claim changes in the private timeline, with explicit current lookup.
The [revision comparison](claim-comparison.md) shows exact typed-object and
predicate/state changes between two selected immutable Claim revisions.

AGENTS.md and PROJECT.md were reviewed before implementation. Their Knowledge
model already specifies Concept/String/Number/Date/Boolean objects. This extends
the existing Knowledge-owned Claim without changing service boundaries, private
defaults, Space authority, Outbox or local transaction ownership.

## Use and scope

개인 개념 보관함 → select a Concept → 개념 관계 → 새 관계의 대상 종류.
Choose 개념, 문자열, 숫자, 날짜 or 참/거짓, enter the value and add the relation.
Existing relations can change their object kind through 관계 편집. Subject and
predicate remain active private Concepts in the same Personal Space. The Claim
ID, subject, independent cl ETag, PRIVATE visibility and DRAFT verification remain.
No automatic publication or verification occurs. Enum/domain-specific schemas,
public Claim review and verification are later work.

CONCEPT is exactly `{"type":"CONCEPT","conceptId":"UUIDv7"}`.
Other objects are exactly `{"type":"NUMBER","value":"12.5"}` (using the chosen type).
NUMBER and BOOLEAN deliberately use JSON strings; no JavaScript numeric coercion
occurs. STRING strips surrounding Java Unicode whitespace and allows up to1000
Unicode codepoints, including supplementary characters. NUL and unpaired
surrogates are invalid. NUMBER accepts plain decimal text, up to38 significant/
integer digits and18 fractional places after normalization; no exponent or
leading-zero numeric notation. Trailing zeros are removed; negative zero is0.
DATE is a valid Gregorian YYYY-MM-DD in0001..9999, with no timezone conversion.
BOOLEAN accepts only the text true or false. Use STRING for identifiers whose
leading zeros must be preserved.

Writes require fresh CONCEPT_WRITE authority, same-origin protection and
If-Match for existing Claims. Claim write bodies allow16384 UTF-8 bytes;
Citation request bodies remain4096. Wrong shape/type returns400, missing/stale
ETag428/412, exact active canonical duplicate409. Typed `"1"` and numeric1 are
distinct objects. Predicate/subject lock ordering serializes duplicate checks
and concurrent transitions without truncating Unicode or hashing away identity.

Only current CONCEPT objects participate in Concept lifecycle and reference
locking. A scalar has no Concept ID. Retiring an old Concept target after a
transition to a scalar does not hide the current Claim. Retiring the current
subject or predicate retains the existing hidden-Claim/history behavior.
PREDICATE usage includes all kinds; OBJECT usage matches Concept objects only.
Scalar objectTitle is its full canonical value.

## Evidence and history

Claim revisions preserve the exact object kind and value. Citation and Qualifier
readers accept both Concept and scalar snapshots. Existing evidence pins do not
follow later Claim edits; repinning requires explicitly inspected current
versions and creates a new independent Citation/Qualifier revision. Old pins
retain the previous kind, value and immutable revision ID. Source reverse
discovery, evidence comparison and integrated detail display the typed value.
Rendering is escaped text. Editors retain failed/stale drafts; revocation clears
private state and parent drafts are independent.

## Events, projections and migration

Knowledge V11 adds nullable object_value to Claim and Claim revision, makes
object_concept_id nullable only for scalars, and applies union constraints.
Existing Concept rows and the existing active Concept-triple unique index remain.
A narrow literal lookup index supports exact canonical duplicate checks under
the same subject/predicate lock. State, revision and Outbox commit atomically.

Existing V1 Protobuf files/artifacts/topics remain immutable. New producers emit
knowledge.claim.v2, knowledge.citation.v2 and knowledge.qualifier.v2 with logical
eventVersion2. Registry artifacts claim-events-v2, citation-events-v2 and
qualifier-events-v2 are independently published at version1 under knowledge, with
FULL validity/FULL_TRANSITIVE compatibility. Inapplicable protobuf ID/value
fields are empty. The standard event/actor/Space/trace envelope remains.

The existing Knowledge connector keeps its identity, database, publication,
replication slot and offsets. Its Outbox route stays knowledge.${routedByValue}.v1.
A precise following RegexRouter maps only claim-v2/citation-v2/qualifier-v2 to
their V2 topics. Other routes are untouched. Literal ACLs grant only the new
producer/validator topics and claim.v2 to the existing network reader.

Brain Query consumes both Claim versions under the unchanged consumer group.
Older V1 replay cannot replace a newer revision. Scalars become Claim body
search text and bounded title previews; they do not create fake Concept nodes.
Concept objects have3 graph edges; scalar objects have2, SUBJECT and PREDICATE.
Full exact text remains searchable while title previews cap the value at200
Unicode codepoints. State/Space enforcement and durable Inbox/materialization
queue semantics remain unchanged.

Canonical recovery preserves byte-identical V1 Concept-object snapshots and
emits V2 for scalar objects. Both validation and rebuild staging choose the
decoder from each envelope. This avoids changing previously recorded synthetic
event hashes. The rollout itself does not reset or rebuild projections.

## Local deployment and validation

1. Publish/verify the three contracts using Run-TypedClaimContractValidation.py,
   Run-TypedCitationContractValidation.py and Run-TypedQualifierContractValidation.py.
2. Upgrade the existing brain-connect chart and apply
   infra/graph/network-kafka-access.yaml; wait for topic/ACL readiness.
3. Run Run-KnowledgeConnectorConfiguration.py with the checked routing config.
   Preserve the existing slot and checkpoint state.
4. Build/import Brain Query, Knowledge and Gateway on both nodes. Run
   Install-LocalTypedClaims.py --helm PATH. It deploys the consumer before
   Knowledge producer, then Gateway, with exact image-import checks.
5. Run Knowledge integration/CDC tests, full backend storage suites, focused
   Run-GraphWebValidation.py --typed-claims-only --no-images and full --public UI.

Deployment rollback must keep a V2-capable Query consumer and the additive V11
schema once scalar data exists. Do not deploy the old Concept-only Knowledge
reader over scalar rows or downgrade the database constraints.

Contracts: personal-concept-claims.yaml, claim-citations.yaml,
personal-claim-qualifiers.yaml, source-citations.yaml and claim-usages.yaml.
Existing Claim/Citation/Qualifier metrics and traces remain. This step adds no
dependency, external dataset or central telemetry platform.
Validation results: typed-claims-validation.txt and .local/typed-claims-proof.json.
