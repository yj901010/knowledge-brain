# Private Note to Concept references

Implemented locally on 2026-10-08: Knowledge-owned personal Concept CRUD/revisions, Content-owned explicit Note-to-Concept IDs, scoped HTTPS authority, independent reference revisions/ETags/Outbox, and Graph View inspector controls. This is a personal Concept foundation. Public Concepts and public Contribution -> Review -> Merge, Claims/Aliases/verification, Concept graph/search projections and registered claiming remain subsequent delivery; private changes never publish public knowledge.

## Ownership and authorization

Knowledge uses only brain_knowledge PostgreSQL with its own restricted account and Flyway V1. UUIDv7 Concept IDs and Space IDs are immutable. Concepts are PRIVATE and ACTIVE/RETIRED; updates and retirement retain canonical revisions. Each browser read/write gets fresh Space CONCEPT_READ/CONCEPT_WRITE authorization using a separate Knowledge service credential. Identity session validation is fresh; caller principal/Authorization headers cannot grant access. Private writes require HTTPS and the configured same Origin, strong If-Match and a secure guest cookie.

Content stores only conceptId/linkId and ordinal in its V6 migration, never a copied title/definition or a foreign key into Knowledge. Link revisions have their own conceptVersion, independent from Note text/version, and strong ETags c0, c1, etc. Reordering retained IDs preserves link IDs. Up to 20 unique UUIDv7 references are accepted in a strict, at most 2 KiB JSON request. Reference mutations, revisions and Protobuf Outbox inserts commit or roll back together under existing Content Space locking; concurrent same-version edits have one winner.

Before adding new IDs, Content calls a fixed HTTPS /internal/knowledge/concept-check with its separate scoped service key and current guest/registered session token. Knowledge verifies fresh destination Space CONCEPT_READ and accepts ACTIVE same-Space PRIVATE or canonical PUBLIC IDs with fresh PUBLIC_CONCEPT_READ in each public catalog Space, in original order; no definition/title is copied. Failure/foreign/unknown IDs prevent mutation. Existing references may remain after Concept retirement, may be removed without Knowledge availability, and remain auditable in reference history. Retirement immediately before/after the synchronous check can occur because transactions stay local; references are immutable IDs, not a promise of continued active status. Note retirement clears current references and emits a final reference revision/event; Concept retirement does not erase Content-owned historical evidence.

## APIs

- GET/POST /api/spaces/{spaceId}/concepts; GET supports UUIDv7 after and limit 1..100 (default 20).
- GET/PUT/DELETE /api/spaces/{spaceId}/concepts/{conceptId}; write body is title + description, If-Match is the quoted positive Concept version. Description is at most 32 KiB UTF-8.
- GET /api/spaces/{spaceId}/concepts/{conceptId}/revisions; before version and limit 1..10. Retired history is retained and authorized.
- GET/PUT /api/spaces/{spaceId}/notes/{noteId}/concepts; body conceptIds, If-Match quoted cN. Response contains noteId, conceptVersion and items of linkId/conceptId only.
- GET /api/spaces/{spaceId}/notes/{noteId}/concepts/revisions; independent reference version cursor and limit 1..10, including retired Note history.

All authorized responses/errors use no-store. Gateway exposes declared routes only, strips caller identity headers and denies internal authority routes. Formal browser contracts: contracts/openapi/note-concepts.yaml; internal batch boundary: knowledge-reference-check.yaml. Space authority endpoint is /internal/space/knowledge-authorization and accepts PRIVATE CONCEPT_READ/CONCEPT_WRITE and scoped PUBLIC_CONCEPT_READ plus contribution actions with the scoped Knowledge key. See public-concept-references.md for the public reference extension.

## Events and recovery

Knowledge Concept changes use knowledge.concept.v1 (knowledge/concept-events), with ConceptCreated/ConceptUpdated/ConceptRetired. Reference changes use content.note-concepts.v1 (content/note-concepts-events), with NoteConceptsChanged, independent Concept reference and Note versions. Both contracts are Protobuf V1 with stable eventId, immutable IDs, Space/actor/trace, producer and payload. They are registered with Registry VALIDITY FULL and COMPATIBILITY FULL_TRANSITIVE. Same-aggregate Kafka ordering and at-least-once delivery apply; future projections must deduplicate by eventId and independent versions.

The new Knowledge CDC identity can SELECT only its own outbox and cannot read canonical Concept rows or connect to the other 12 owner databases. Its connector snapshots only that outbox, uses its own replication slot/publication and raw Protobuf BinaryDataConverter. Connect topic Write ACLs and read-only validator identities are literal/bounded. No new canonical consumer or read projection is introduced here; canonical references and history already survive service restarts.

## UI and deploy

Start scripts/Start-LocalGateway.ps1 in host PowerShell and visit https://localhost:8443/graph. Select a Note, then use the inspector's personal Concept name field and checkboxes to create/link/unlink a Concept. The personal catalog now uses 20-item cursor pages with explicit more/refresh controls and no 100-item UI cutoff; IDs outside the loaded active catalog remain removable without showing copied private labels. Public references use the existing public picker. Existing Note graph vertices/edges remain Note-to-Note; Concept graph vertices remain future scope. Link conflict recovery and canonical link history are described in note-concepts-ui.md.

Build/import each of knowledge, space, content and gateway with existing Build-LocalSpaceImage.py / Import-LocalSpaceImage.py --service NAME. Wait for both import Jobs before installation. Run Install-LocalNoteConcepts.py --helm PATH. It preserves existing IDs/data/PVCs, provisions separate keys/TLS, deploys Space and Knowledge/migrations, bootstraps own Knowledge CDC, waits for Kafka topics and scoped ACLs, publishes contracts/configures connectors, then exposes Content/Gateway. This ordering keeps canonical writes behind initialized event transport. The same Helm logical architecture is retained across environments.

Validate using:

```powershell
python scripts/Run-BackendValidation.py --graph-storage --search-storage --content-kafka --knowledge-kafka --space-kafka --media-storage --media-kafka --cdc
python scripts/Run-NoteConceptApiValidation.py
python scripts/Run-NoteConceptApiFailureValidation.py
python scripts/Run-GraphWebValidation.py
```

The fault coordinator creates its own confined Job, replaces only Knowledge Pods, restores the original replica count in finally, and saves UID proof in .local/note-concept-api-failure-proof.json. Browser validation uses the public local CA without TLS bypass and fresh isolated guest fixtures. Evidence and deployment artifacts: [note-concepts-validation.txt](note-concepts-validation.txt).

Paged personal Concept selection, explicit reference conflict recovery and canonical link history UI: [note-concepts-ui.md](note-concepts-ui.md).

Relationship history is also available in the private Activity timeline: [Note Concept activity](note-concept-activity.md).

## Compare retained revisions

Personal Concept and Source histories now compare any loaded earlier/later pair,
including retired archives, with exact fields and audit metadata. See
[knowledge-revision-comparison.md](knowledge-revision-comparison.md).
