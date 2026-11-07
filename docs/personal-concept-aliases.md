# Personal Concept Aliases

AGENTS.md and PROJECT.md were reviewed before implementation. Alias is explicitly
Knowledge-owned. Personal aliases use the current Space CONCEPT_READ/CONCEPT_WRITE
authority, including the common guest/registered principal. There is no service
boundary, database ownership or public contribution policy change, new dependency
or external terminology dataset.

Open **개인 개념 보관함**, select a Concept and use **개념 별칭** below its editor.
The catalogue loads actual 20-row UUIDv7 cursor pages. Add a different name with
**새 별칭 → 별칭 추가**. Selecting **별칭 열기** opens explicit edit, history and
retirement actions. Counts describe only loaded active rows. Alias and Concept
editors are separate siblings, so Alias reads/writes do not reset Concept or Note
drafts. Existing Content Concept references and their c-ETags are unchanged.

Aliases are PRIVATE and SPACE-scoped. Each has an immutable UUIDv7 aliasId and
parent Concept/Space IDs, independent version and strong `"aN"` ETag. The canonical
label strips surrounding Java whitespace, then requires 1..200 Unicode code
points, no NUL and no unpaired surrogates. Requests are limited to 4096 bytes.
Exact case-sensitive active labels are unique within one Concept; other Concepts
may use the same label. No case folding or Unicode normalization is performed.
There are at most 100 active aliases per Concept. Retired labels may be reused
with a new ID, preserving the previous Alias's history.

## API and lifecycle

The base path is `/api/spaces/{spaceId}/concepts/{conceptId}/aliases`.

| Operation | Result |
| --- | --- |
| GET base, `after`/`limit` | `{items: Alias[], nextAfter: UUIDv7 or null}`; default 20, maximum 100 |
| POST base, `{label}` | 201, Location, independent `"a1"` ETag |
| GET `/{aliasId}` | Active canonical Alias and `"aN"` ETag |
| PUT `/{aliasId}`, `{label}`, If-Match | Updated Alias and incremented Alias ETag |
| DELETE `/{aliasId}`, If-Match | Logical retirement, 204 and incremented Alias ETag |
| GET `/{aliasId}/revisions`, `before`/`limit` | Immutable revisions in descending version order; default 5, maximum 10 |

IDs/cursors require canonical UUIDv7. Query keys must be known and single-valued.
Revision `before` is exclusive and defaults to Long.MAX_VALUE. Missing If-Match
is 428; weak, wildcard, multiple or another aggregate's ETag is 400; stale version
is 412. Duplicate active label or active capacity exhaustion is 409. Unknown,
foreign, public or retired parents cannot be read or mutated through active Alias
APIs. Writes require HTTPS, the configured exact browser Origin and fresh Space
and Identity authorization. Private responses use no-store. Authority failures
stop before any canonical lookup or mutation. OpenAPI:
[personal-concept-aliases.yaml](../contracts/openapi/personal-concept-aliases.yaml).

An Alias mutation locks its active PRIVATE parent Concept, then the Alias. The
same parent lock is used by Concept retirement, so no write can commit after a
previously committed parent retirement. List/get join only same-owner, same-Space
active Concepts. Parent retirement retains Alias rows and revisions; it does not
cascade delete them or synthesize AliasRetired events. Downstream consumers must
combine independent Alias and Concept lifecycle states before displaying active
results. Known Alias history remains authorized and readable after either
retirement. The UI keeps the selected Alias history available after parent
retirement. The explicit retired Alias archive now supports rediscovery after
closing/reloading; restoration remains outside the implemented scope.

The UI retains input on validation, duplicate, failure and 412 responses. It never
automatically repeats a POST. On conflict, **최신 별칭 확인** displays the current
label without overwriting the draft; **최신 별칭 버전으로 다시 시작** explicitly
replaces it. Retirement requires acknowledgement of the displayed version, and a
conflict requires fresh confirmation. Request generations, selected ID checks,
AbortController and eight-second bounds discard results from a closed/switched
view. Catalogue failures clear rows for explicit retry. 401/403/404 clear private
parent state. No localStorage/sessionStorage is used. Aborting a browser request
cannot undo a transaction already committed by Knowledge.

## Persistence and events

Knowledge Flyway V3 adds `alias` and `alias_revision`. A composite Concept FK
enforces same Space ownership within the Knowledge database. A partial active
label unique index and parent locking serialize duplicate/capacity checks.
Successful create/update/retire atomically write the Alias, immutable revision and
transactional Outbox. Failed/stale writes produce neither revisions nor events.
Concept title, description, version, revisions and events remain independent.

`AliasCreated`, `AliasUpdated` and `AliasRetired` carry complete Protobuf snapshots
through the existing Debezium Knowledge connector to `knowledge.alias.v1`, keyed
by aliasId. A separate immutable schema `knowledge/alias-events` version 1 uses
VALIDITY FULL and COMPATIBILITY FULL_TRANSITIVE; published Concept V1 bytes are
unchanged. The envelope carries eventId, producer, aggregate/Space/actor/trace IDs
and revision/version. Consumers deduplicate eventId and order by aliasId/version.
Connect has explicit write permission and the Knowledge validator has explicit
read permission for this literal topic. Personal labels do not become public or
universal aliases and do not automatically resolve Wiki Links.

Metrics: `brain.knowledge.alias.changes{type=created|updated|retired}` and
`brain.knowledge.alias.reads{type=list|retired_list|get|history}` plus current Gateway metrics,
database readiness and trace propagation.

## Local delivery and validation

Apply the brain-connect chart with local values; wait for the Alias topic and
Kafka users Ready. Run `scripts/Run-AliasContractValidation.py` and inspect its
Complete Job. Build/import Knowledge and Gateway using existing local helpers,
wait for every printed node import Job, then run
`scripts/Install-LocalPersonalConceptAliases.py --helm PATH`.

`scripts/Run-BackendValidation.py --knowledge-kafka` runs the full reactor with
real PostgreSQL and Knowledge CDC/Kafka. `scripts/Run-GraphWebValidation.py
--concept-aliases-only` runs the focused actual API/browser scenario; the full
`--public` suite includes it. Evidence is recorded in
[personal-concept-aliases-validation.txt](personal-concept-aliases-validation.txt).

## Retired child archives

Explicit read-only discovery of retired Aliases and External Mappings is now
available from active Concept panels and the retired Concept archive. See
[retired-concept-details.md](retired-concept-details.md) for query semantics,
immutable pins, UI behavior and validation. Existing active-list defaults remain.
