# Private web bookmarks

Refresh `/graph` → **개인 북마크 열기** → **새 북마크 작성**. Enter a title,
web address and plain-text memo, then save. The compact catalog loads20 at a time;
open a record to edit, inspect immutable versions, or explicitly confirm archival.
Select **북마크 보관 상태 → 보관한 북마크** to rediscover archived records/history.
The same address may be saved as separate bookmark IDs with different memos.

Opening a bookmark never fetches the target. Only the explicit **북마크 외부 링크
열기** link navigates to a new tab with noopener/noreferrer and no referrer. No
preview, favicon, remote metadata extraction or automatic Note/Concept creation
occurs. Title/memo are escaped text and retain whitespace. The feature follows
PROJECT.md's existing Content ownership, private default and common principal;
no existing service/database/event architecture is restructured.

## Canonical data and API

Content V8 owns bookmark and bookmark_revision in brain_content. All IDs are
UUIDv7. Bookmark state/version/current JSON, immutable revision, original creation
intent hash/key and standard Protobuf Outbox bytes are committed in one local
ACID transaction. No other service SQL, joins, foreign keys or mutations occur.
Bookmark edits never rewrite Note, Knowledge, Learning, Planning or Notification
facts. Own revision FK and unique Space/request-key constraints preserve identity.

[private-bookmarks.yaml](../contracts/openapi/private-bookmarks.yaml) documents six
operations: collection GET/POST, object GET/PUT/DELETE, and GET object/revisions.
All requests recheck fresh BOOKMARK_READ/WRITE through the existing dedicated
Content→Space capability and common guest/registered principal. Only PERSONAL/
PRIVATE Spaces are supported. Visible members read; OWNER/ADMIN/EDITOR write.
The existing service credential is reused, with explicit new actions added to
[Space's capability contract](../contracts/openapi/space-guest.yaml). Note actions
and their permissions remain unchanged. Internal paths are never Gateway routes.

Input has exactly title, url, memo. Title is nonblank, at most200 Unicode
codepoints/800 UTF-8 bytes, with no newline/tab. Memo is at most32768 UTF-8 bytes;
CR/LF/tab are retained, other ISO control and unpaired surrogates rejected.
URL is an exact literal ASCII absolute HTTP/HTTPS URI of at most2048 bytes, with
a host, no credentials, whitespace, control, backslash or ambiguous authority.
Optional ports must be1..65535. International paths/queries use percent encoding;
international hostnames use punycode. Case, query, percent-escape spelling and
fragment are stored verbatim; there is no DNS/remote lookup or normalization.

TLS, singleton secure browser credential, same-origin mutation, no-store, exact
known singleton queries, empty GET/DELETE, exact JSON (including duplicate/trailing
field rejection) and a256 KiB JSON transport limit apply. Strong independent
`bmN` tags guard writes. Missing tag428; malformed400; stale412, checked before
no-op comparison. Current no-op emits nothing. Known retired GET/history remains
200; retired edits404; archival returns204, including matching-version no-op.

Create requires one unpredictable32-lowercase-hex Idempotency-Key. Same Space/key
and validated input replay the **original v1 and bm1**, even after edits, archival,
restart or guest account claim. Different input409; concurrent identical creates
produce one object/event. Failed Outbox insertion rolls back creation, key, revision
and mutation. A lost response freezes the same browser fields/key for retry;
inputs/keys are held in memory only. Inspect the catalog after abandoning an
uncertain creation form before starting another explicit intent.

Catalogs have ascending opaque cursors bound to Space/lifecycle,20 default/100 max.
Summaries omit memo to keep pages bounded; full text belongs to canonical detail.
Pages are live views rather than frozen snapshots. Audit is descending exclusive
version,5 default/10 max. Account claim preserves IDs, original guest actors,
revision IDs, input hashes and request keys; subsequent edits record the registered
actor. A 412 preserves Bookmark drafts until explicit latest inspection/restart.
Archive confirmation resets after conflict. Existing Note and sibling drafts remain
independent. Failed catalogs clear stale rows; close/Space changes discard late
responses, and authority loss clears private surfaces. No private browser storage.

## Events, operations and validation

Content transaction → existing Outbox/publication → existing content-outbox
Debezium connector → literal **content.bookmark.v1**. New immutable Registry
`content/bookmark-events/1` has FULL validity and FULL_TRANSITIVE compatibility.
Types BookmarkCreated/Updated/Retired contain full immutable private revision,
standard11 envelope fields, original actor/Space and HTTP trace. At-least-once
consumers must deduplicate stable event IDs. No existing schema is changed.

The existing Connect producer and read-only Content validator receive the one
additional literal topic permission; existing app projectors retain their original
streams. The bookmark catalog reads its single owner's canonical API. Integrated
Bookmark graph/search/activity projections and explicit domain links are separate
increments, using these events rather than cross-owner SQL. Existing Note/Concept
graph/search and Learning/Planning activity semantics remain intact.

Existing reviewed Java25/Spring/JDBC/Flyway/Protobuf/Kafka/Next dependencies, TLS,
nonroot/read-only filesystem, virtual threads, own DB pool/readiness, bounded
Bookmark read/change counters and trace→Outbox→Kafka headers are reused. No new
dependency, dataset, external credential or AI capability is introduced. Central
OpenTelemetry exporters/Prometheus/Loki/Tempo/Grafana remain platform work.

Local sequence: publish Run-BookmarkContractValidation.py; upgrade existing
brain-connect chart; provision the Content read-only validator; build/import on
both nodes and install Space, Content, then Gateway independently. After own V8
migration, run Provision-ContentCdc.py: Outbox SELECT allowed, Note and two Bookmark
canonical SELECTs denied, twelve other owner DB CONNECTs denied. Existing connector,
slot, publication and offsets remain; no source data, topic or PVC reset.

Backend validation uses --content-kafka --planning-kafka --notification-kafka
--activity-storage. Focused browser uses --bookmarks-only; full browser uses --public
and includes real account claim. Evidence: [private-bookmarks-validation.txt](private-bookmarks-validation.txt).
