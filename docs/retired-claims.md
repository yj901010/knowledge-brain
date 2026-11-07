# Retired private Claim discovery

Open `/graph` → 개인 개념 보관함 → select a Concept → **폐기한 관계 열기**.
This independent, read-only catalogue lists explicitly retired relationships in
20-row creation-order pages. Select a row to inspect its original revision IDs,
actors, timestamps, predicate IDs and exact typed values, or compare two versions.
History pages contain five revisions. Close/reopen the catalogue or reload the
page and reopen the subject to find the same retained relationships again.

The catalogue does not replace the active relationship editor. Active Claim and
Concept drafts remain independent. Retiring a referenced Concept may hide an
ACTIVE Claim; such a Claim is not presented as explicitly RETIRED. A retired
subject is supported by the API and by an already selected Concept detail after
retirement. The [retired Concept archive](retired-concepts.md) also lets users find
that subject again after closing its detail or reloading the browser.

## Contract and ownership

`GET /api/spaces/{spaceId}/concepts/{subjectConceptId}/claims` now accepts a
single `state=ACTIVE|RETIRED` parameter. Omission retains the existing ACTIVE
semantics. `RETIRED` requires a known PRIVATE subject in the authorized Space;
it accepts retired subject, predicate and object Concepts. Only matching PRIVATE,
DRAFT, explicitly retired Claim records are returned. `after` is an exclusive
UUIDv7 cursor and `limit` remains 1..100 (default20). Repeated, empty, unknown or
noncanonical values are rejected. Responses remain no-store.

Individual current Claim GET/PUT/DELETE remain active-only; this feature does
not restore Claims, modify revisions, promote verification, or publish content.
The existing revision endpoint supplies the archived history. Current names are
not substituted for stored historical Concept IDs. Numbers stay exact strings;
all five object kinds use the existing typed-value contract.

Every list/history request uses fresh Space/Identity authorization. Gateway
allows the state key only on the exact Claim collection GET. Aliases, mappings,
Citations, Qualifiers, current detail, history and write routes do not acquire
this parameter. The UI validates Space/subject/Claim identity, retired state,
cursor ordering and immutable revision metadata before rendering. Initial history
must match the selected retired row; duplicate revision IDs are rejected within
and across pages. Failure clears stale results, delayed replies cannot repopulate
a closed/switched scope, and revocation clears private state. No browser storage.

AGENTS.md and PROJECT.md were reviewed. No decision conflicts: Knowledge retains
canonical ownership and existing local transactions/Outbox; Space owns authority.
V12 adds only a partial index for `(space_id, subject_concept_id, claim_id)` over
RETIRED rows. No canonical facts, events, projections, checkpoints or contracts
for Kafka are rewritten. Existing Claim read metrics add the bounded
`type=retired_list` value. There are no new dependencies or datasets.

## Local delivery

Build/import Knowledge and Gateway using the existing Build-LocalSpaceImage.py
and Import-LocalSpaceImage.py service arguments. Verify both local-node imports,
then deploy the existing Knowledge Helm release before Gateway, using their base
and local values with the exact generated image tags. No projector rebuild is
needed. The additive index is safe for existing readers.

Checks: `Run-BackendValidation.py --knowledge --knowledge-kafka`,
`Run-BackendValidation.py --gateway`, and
`Run-GraphWebValidation.py --retired-claims-regressions --no-images` after rollout.
Results: `retired-claims-validation.txt` and `.local/retired-claims-proof.json`.

The selected retired Claim history also exposes the read-only
[unlinked Citation archive](retired-citations.md), preserving original Claim and
Source pins without restoring or re-pinning them.

It also exposes the [retired Qualifier archive](retired-qualifiers.md) for original
typed condition values and their pinned Claim histories.
