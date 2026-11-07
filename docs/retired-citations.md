# Unlinked private Citation archive

Open `/graph` → **개인 개념 보관함 열기** → select a Concept and Claim →
**해제한 출처 연결 열기**. The independent archive lists directly
unlinked Citations in twenty-row UUID creation-order pages. Stable Citation IDs
distinguish repeated links to the same Source. Select a row for five-revision
history pages, pinned Claim and Source versions, exact typed Claim objects,
Source title/URL/citation/license notes and revision/actor/time audit metadata.
Older revisions may pin a different Source; they retain their original snapshots.

The same archive appears below a selected retired Claim's valid history. A
retired subject can be reached through **폐기한 개념 열기**, then **폐기한 관계
열기**. This allows rediscovery after browser reload even when the Claim,
subject, predicate, object or Source has since retired. Explicitly unlinked
Citations remain distinct from ACTIVE Citations hidden by retired references.
Source-based inverse citation discovery continues to show effective ACTIVE rows.

Archive actions are read-only GETs. They do not restore, re-pin, publish or
rewrite any relationship, verification state or revision. Active Claim/Citation
drafts remain independent. Safe HTTP(S) links require explicit navigation;
URLs are never automatically fetched. Text is escaped and browser storage is
unused. Invalid/failed responses clear stale details; aborted or late responses
cannot repopulate closed or switched scopes. Authorization denial clears private
UI. The client checks Space/parent/Claim/Citation identity, PRIVATE/RETIRED state,
typed objects, metadata bounds, URLs and exclusive ordered cursors. The history
head must match the selected row's version and every pinned field. Revision IDs
must be unique within and across history pages.

## Contract and compatibility

`GET /api/spaces/{spaceId}/concepts/{subjectConceptId}/claims/{claimId}/citations?state=RETIRED`
returns the existing Citation page shape. Optional state defaults to ACTIVE;
only exact ACTIVE or RETIRED is accepted. Fresh common guest/registered Space
authorization is required on every read; responses use no-store. UUIDv7 after
is exclusive; limit is 1..100, default20. Invalid, duplicate and unknown queries
fail. Only the exact nested Claim Citation collection GET accepts state; item,
history, write and Source-based citation routes retain their existing contracts.
Current item and mutation lifecycle rules remain unchanged.

AGENTS.md and PROJECT.md were reviewed with no decision conflicts. Knowledge
owns all canonical data and SQL. V15 adds a partial index on
`(space_id, subject_concept_id, claim_id, citation_id) WHERE state='RETIRED'`.
Reads leave immutable revisions, Outbox, events and projection checkpoints
unchanged. Existing citation read metrics add bounded `type=retired_list`;
existing HTTP tracing applies. No new dependencies or datasets are introduced.

## Local delivery

Run `Run-BackendValidation.py --knowledge --knowledge-kafka` and `--gateway`.
Build/import exact Knowledge and Gateway images on both local nodes, deploy
Knowledge before Gateway through existing Helm releases, then run
`Run-GraphWebValidation.py --retired-citations-regressions --no-images` against
the deployed HTTPS application. No projection rebuild is needed. Results are
recorded in `retired-citations-validation.txt` and `.local/retired-citations-proof.json`.
