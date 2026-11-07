# Retired private Alias and External Mapping archives

Open a personal Concept and choose **폐기한 별칭 열기** or
**폐기한 외부 연결 열기**. Each archive paginates 20 records in UUIDv7 creation
order and opens immutable history five revisions at a time. Repeated labels or
system/code/edition tuples remain separate records, identified by stable UUID.
Closing/reopening and reloading rediscover the same records. The retired Concept
archive also exposes both archives after its selected history has loaded.

Alias history shows the exact prior label, revision, actor and timestamp within
its PRIVATE SPACE scope. External Mapping history shows case-sensitive system,
opaque code (including leading zeros), optional edition and label, CANDIDATE
state, and the pinned Concept and Source revisions. Empty edition means
unrecorded. Later edits or retirement of the parent Concept or Source do not
replace stored pins. A historical revision can reference a different Source.
An external URL is shown only as an explicit safe HTTP(S) link; viewing does not
fetch external content or determine its licensing or mapping correctness.

## API and ownership

The existing Knowledge collection GETs accept an optional exact `state`:

- `/api/spaces/{spaceId}/concepts/{conceptId}/aliases`
- `/api/spaces/{spaceId}/concepts/{conceptId}/external-mappings`

Omitted state and `ACTIVE` preserve existing behavior under an active private
parent. Explicit `RETIRED` reads only directly retired private child records
under a known same-Space private Concept, even if that parent is retired.
Retiring a parent does not implicitly retire its active children. `after` is
exclusive, `limit` defaults to 20 and is bounded 1–100. Empty, repeated, unknown
or misplaced parameters are rejected. Item, write and history routes do not
accept state. Existing revision routes retain exclusive `before` and limits
1–10. No restore, repin, verification or publication action is introduced.

Every request uses fresh Identity/Space authority and `Cache-Control: no-store`.
Foreign/public/unknown parents are unavailable. Knowledge owns all queried
canonical rows and revisions; Gateway forwards only exact allowed routes and
query shapes. V17 adds partial retired-row indexes for both collections.
No canonical rewrite, event contract, projection or dependency changes occur.
Bounded read metrics add `type=retired_list` to the existing Alias and external
mapping metrics. Query errors keep existing status and tracing behavior.

## Browser behavior and validation

Each archive keeps list and history requests separate, with aborts, eight-second
timeouts and scope/selection guards. Close, selection changes and unmount discard
late replies. Failed/malformed reads clear previous results and offer explicit
retry. Responses are checked for identity, private state, strict ordering,
cursor bounds, exact text limits, head-snapshot agreement and duplicate revision
IDs. Authority denial clears the private parent flow. Independent Concept,
Alias and Mapping edit drafts survive read-only archive navigation; private
records are not stored in browser storage. Text renders escaped and wraps at
mobile widths.

Run Knowledge tests with `Run-BackendValidation.py --knowledge --knowledge-kafka`
and Gateway tests with `--gateway`. After the pinned frontend build, build/import
both images on all local nodes and deploy Knowledge before Gateway. Run
`Run-GraphWebValidation.py --retired-concept-details-regressions --no-images`.
The six new browser scenarios cover rediscovery, exact pins, drafts, retired
parent navigation, bad responses, late requests and actual session revocation.
Results: [retired-concept-details-validation.txt](retired-concept-details-validation.txt).
