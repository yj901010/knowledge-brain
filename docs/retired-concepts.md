# Retired personal Concept archive

Open `/graph` → **개인 개념 보관함 열기** → **폐기한 개념 열기**.
The independent archive lists retired private Concepts in 20-row creation-order
pages. Select a Concept to read its immutable title, description, revision ID,
actor and timestamp. Load five older revisions at a time. The selected archive
also opens **폐기한 관계** with retained Claim history and version comparison,
even when the subject Concept is retired. Closing the detail or reloading the
browser no longer prevents finding the retired subject again.

Active Concept editing and new Concept drafts remain independent. The archive
performs only GET requests, does not restore or publish data, and does not turn
hidden ACTIVE Claims into explicitly retired Claims. It stores no browser data.
Historical text renders as literal text. Errors clear stale results; closed or
switched requests cannot repopulate a new scope. Revocation clears private UI.
Summary cursor/order and revision identity, version, title, retired head and
duplicate IDs are validated before rendering. Nested Claim history mounts only
after valid Concept history is loaded.

## API, ownership and compatibility

`GET /api/spaces/{spaceId}/concepts?state=RETIRED` uses the existing summary page
shape. State is optional and defaults to ACTIVE. RETIRED selects only PRIVATE
rows in the authorized Space. Both states use UUIDv7 exclusive `after` and a
1..100 limit (default20). Gateway permits the new filter only on this exact
private collection GET; Concept item/history/write routes and public routes do
not accept it. Unknown or repeated keys fail. All responses use no-store and
fresh Space/Identity authority. Existing Concept revision reads remain available
after retirement; current detail and changes remain active-only.

AGENTS.md and PROJECT.md were reviewed with no decision conflict. Knowledge owns
all reads; no cross-service SQL or new aggregate/event is introduced. V13 adds
one partial `(space_id, concept_id)` index over PRIVATE RETIRED rows. Immutable
revisions, canonical content, Outbox events and projection checkpoints remain
intact. Bounded Concept read metrics expose `type=list|retired_list`; HTTP tracing
uses the existing infrastructure. No dependencies or datasets are added.

`note-concepts.yaml` documents the state filter and the actual Concept summary,
detail and revision response shapes (previously shared Note-reference schemas).

## Validation and local delivery

Use `Run-BackendValidation.py --knowledge --knowledge-kafka`, then the separate
`--gateway` run. Build/import Knowledge and Gateway with existing scripts and
verify imports on both local nodes. Deploy Knowledge before Gateway through the
existing Helm releases and generated image tags. No projection rebuild is needed.
Run `Run-GraphWebValidation.py --retired-concepts-regressions --no-images` against
the deployed HTTPS application. Results are recorded in
`retired-concepts-validation.txt` and `.local/retired-concepts-proof.json`.

## Retired child archives

Explicit read-only discovery of retired Aliases and External Mappings is now
available from active Concept panels and the retired Concept archive. See
[retired-concept-details.md](retired-concept-details.md) for query semantics,
immutable pins, UI behavior and validation. Existing active-list defaults remain.

## Compare retained revisions

Personal Concept and Source histories now compare any loaded earlier/later pair,
including retired archives, with exact fields and audit metadata. See
[knowledge-revision-comparison.md](knowledge-revision-comparison.md).
