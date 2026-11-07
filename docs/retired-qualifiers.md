# Retired private Claim Qualifier archive

Open `/graph` → **개인 개념 보관함 열기** → select a Concept and Claim →
**폐기한 관계 조건 열기**. Twenty-row creation-order pages rediscover directly
retired conditions after closing or reloading the browser. Select a stable
Qualifier ID to read five-revision history pages, condition predicate/value,
the immutable pinned Claim version and object, and revision/actor/time metadata.
STRING, exact decimal NUMBER, DATE, BOOLEAN and CONCEPT values remain distinct.
Repeated conditions keep distinct IDs; reopening never restores or duplicates them.

The archive is also available under a selected retired Claim's valid history.
Retired subjects are reached through **폐기한 개념 열기** and **폐기한 관계 열기**.
Conditions and their pins remain readable after their parent Claim or referenced
Concepts retire. ACTIVE conditions hidden by retired parents are excluded.
Later Claim edits do not change the historical pin or condition value.

Archive actions use GET only and preserve independent active Claim and Qualifier
drafts. No external requests, browser storage, HTML interpretation, publication,
restoration, repinning or verification changes occur. The client validates exact
Space/parent/Claim/Qualifier identities, PRIVATE/RETIRED state, typed values,
decimal bounds, ordered exclusive cursors and history identity. A fresh history
head must match every selected value and Claim pin. Duplicate revision IDs
within/across pages fail. Failed or malformed reads clear stale data and support
explicit retry; late responses cannot repopulate closed or switched scopes.
Authorization denial clears private UI.

## API and compatibility

`GET /api/spaces/{spaceId}/concepts/{subjectConceptId}/claims/{claimId}/qualifiers?state=RETIRED`
uses the existing page shape. Optional state accepts exact ACTIVE or RETIRED;
default ACTIVE preserves existing effective-parent rules. RETIRED requires the
retained PRIVATE parent Claim and subject within the freshly authorized Space.
Common guest/registered authority is checked on every read, with no-store
responses. UUIDv7 after is exclusive; limit is 1..100, default20. Unknown,
duplicate or invalid query parameters fail. Only this exact collection GET
accepts state; item, history and write query contracts remain unchanged.

AGENTS.md and PROJECT.md were reviewed; no established decision conflicts were
found. Knowledge owns all SQL and canonical data. V16 adds only the partial
`(space_id, subject_concept_id, claim_id, qualifier_id) WHERE state='RETIRED'`
index. Reads leave Qualifier/Claim revisions, Outbox, events and projection
checkpoints untouched. Existing qualifier read metrics add bounded
`type=retired_list`; existing HTTP traces apply. No dependencies or datasets added.

## Local delivery

Run `Run-BackendValidation.py --knowledge --knowledge-kafka` and `--gateway`.
Build the frontend/Gateway first, then Knowledge, import exact images onto both
local nodes and roll out Knowledge before Gateway through existing Helm releases.
Run `Run-GraphWebValidation.py --retired-qualifiers-regressions --no-images` on the
deployed HTTPS app. No projection rebuild is required. Results are recorded in
`retired-qualifiers-validation.txt` and `.local/retired-qualifiers-proof.json`.
