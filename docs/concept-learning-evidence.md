# Concept learning evidence

Open a private Personal Roadmap and select **개념 학습 근거 열기**. Choose an
active node and a Concept from the same personal Space. To reference a public
Concept, explicitly load **공개 개념 공간 조회**, choose the source Space and Concept,
and write an optional learning memo. The Concept picker and evidence catalog page
20 at a time. Search filters the loaded Concept titles; IDs distinguish duplicates.
Public catalog failure does not prevent private Concept selection. Switching source
Space clears the selection and cancels its pending reads while retaining the memo.

The Learning relationship is PRIVATE even when the source Concept is PUBLIC. It
records the selected Concept's immutable ID, source Space, version, title and
source visibility at linking, plus node ID/title and the user's memo. It does not
copy descriptions, Claims, citations or canonical knowledge. This is a personal
learning record, not automatic mastery, completion, medical verification, public
contribution or ranking. Existing Note/Roadmap drafts and manual progress remain
independent. Current source information is retrieved only via **현재 개념 확인**;
later Concept edits or retirement do not rewrite the original pin.

Learning continues to own `learning_evidence` and `learning_evidence_revision`.
Migration V4 adds a checked NOTE/CONCEPT discriminator and external Concept source
columns, preserving existing NOTE IDs, JSON revisions, request fingerprints and
Outbox bytes. Each row must have exactly one valid source shape. There is no
cross-service foreign key, SQL, join, write or distributed transaction. The Note
API and Note Protobuf v1 are unchanged; its reads only return NOTE rows. CONCEPT
rows have a separate typed collection at
`/api/spaces/{spaceId}/roadmaps/{roadmapId}/concept-evidence`, and IDs from the wrong
variant do not resolve on either endpoint. History is likewise variant- and
Space-scoped. A real PostgreSQL migration test starts from V3 rows, upgrades to V4
and verifies exact retained Note data, fingerprints, retry semantics and bytes.

Before a new link Learning authorizes current ROADMAP_WRITE on the destination
private Personal Space. Through HTTPS it calls the narrow Knowledge owner read
`/api/spaces/{spaceId}/concept-references/{conceptId}/learning-reference`, carrying
only the common session Cookie and trace context. Knowledge independently checks
CONCEPT_READ on that destination, then selects an ACTIVE same-Space PRIVATE or
PUBLIC source in one own-DB query. Foreign private/group/organization data does
not match. A PUBLIC source additionally requires the public feature flag and
current PUBLIC_CONCEPT_READ on the canonical source Space by the same principal.
Unmerged proposals cannot be referenced. The reply contains only source metadata,
destination Space and principal, with no description or credential. Learning
checks the echoed destination, actor, source ID/Space/visibility and expected
version. A source Space mismatch is 404; changed version is 409, never an implicit
advance to the latest. Gateway does not route this owner-only endpoint. Knowledge
ingress now permits Learning; no cross-owner DB privilege or service-wide snapshot
credential is added. Every user operation reauthorizes the destination Space.

A local root lock serializes new links with graph/node retirement. New links
require active root/node/source at the owner check. The source can change after
the check and before commit; the pin is historical, not a liveness guarantee.
Existing records remain readable with current destination Space permission after
node/source/root retirement. Memo corrections and soft retirement require an
active root, but an archived source/node does not prevent those audit operations.
Retirement excludes the record from the current catalog while preserving history.

POST accepts exactly `{nodeId, conceptSpaceId, conceptId, conceptVersion, memo}`
with one opaque 32-character lowercase hex `Idempotency-Key`. Learning generates
UUIDv7 evidence/revision/event IDs. Keys are unique across BOTH variants within a
Roadmap. Same original key and payload replay the CURRENT CONCEPT record with 200,
including retired records/root/source, without another owner check or event.
Another payload or variant with that key returns 409. New links return 201; a
duplicate active node/Concept-Space/Concept/version pin under another key is 409.
Memo edits do not change the source pin. Client tokens and drafts stay only in
component memory; retry after a lost committed response creates no duplicate.

The independent strong ETag is `"ceN"`; NOTE retains `"evN"`. PUT accepts only
`{memo}`. DELETE has no body and requires explicit fresh UI confirmation, reset
on a 412. Missing/wrong/stale ETags are 428/400/412. No-op memo writes still check
version and emit nothing. Strict JSON rejects unknown/duplicate/trailing fields,
non-integral/out-of-range versions and bodies above 65536 bytes. Raw memos are
limited to 8192 UTF-8 bytes with disallowed controls rejected. Catalog keysets are
ascending UUID with default20/max100; immutable history is descending version
with default5/max10. Responses are no-store; browser mutations require same origin.

Each real change commits the relationship, full immutable revision and full
Protobuf Outbox atomically. The independent `learning.concept-evidence.v1` topic
and Registry `learning/concept-evidence-events` v1 use stable event IDs and
`ConceptLearningEvidenceLinked/Updated/Retired`. Payloads include all historical
pins/memo/state and revision ID, with actor/Space/trace/schema headers. Existing
Note events never change wire type or artifact. Register with FULL validity and
FULL_TRANSITIVE compatibility; apply exact-topic Connect write and Learning
validator read ACLs BEFORE the first new event/test. The existing Learning
Outbox-only publication/slot/offsets are preserved. Canonical table/other-owner DB
CDC denials remain unchanged. Counters `brain.learning.concept.evidence` and
`brain.knowledge.learning.references` use bounded operation/visibility labels;
HTTP metrics, readiness and HTTP-to-Outbox-to-Kafka traces remain. Central
observability stack/exporter deployment is still separate platform work.

Local deployment uses the existing image build/import workflow for Knowledge,
Learning and Gateway on both k3d nodes. `Install-LocalRoadmaps.py` now accepts
`--knowledge-only` as well as the existing `--learning-only` and `--gateway`, each
with `--helm`. Apply the Connect chart and wait for the topic and both KafkaUsers
to reconcile Ready before writing evidence, then run the tracking contract
publisher and CDC privilege verifier. Do not reset slots, offsets or databases.
Validate with `Run-BackendValidation.py --learning --learning-kafka`, the full
reactor, `Run-GraphWebValidation.py --concept-learning-evidence-only`, and full
`--public --no-images`. Full public validation covers unmerged/public sources,
immutable pins through actual reviewed Concept update, public picker UI, and two
real guest-to-Keycloak Space claims preserving Concept evidence IDs and snapshots.

Media evidence, optional StudySession association, Activity timeline projection,
public/group Roadmap learning and ratings/ranking remain follow-up. Existing
reviewed dependencies and pinned containers are reused; no package, dataset or
license was added. Visual redesign remains deferred in favor of functionality.

Media evidence is now available in its separate typed collection; see [media-learning-evidence.md](media-learning-evidence.md). Existing Note/Concept endpoints and immutable v1 contracts are retained. Request keys are shared across all three evidence variants.

Optional StudySession association is now available through a separate typed relationship; see [evidence-study-sessions.md](evidence-study-sessions.md). Existing evidence/session JSON and immutable event v1 contracts remain unchanged.
