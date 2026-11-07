# Personal Sources

AGENTS.md and PROJECT.md were reviewed before implementation. Source metadata
and immutable revisions remain Knowledge-owned within the current service,
Space, PostgreSQL, transaction and Outbox/CDC boundaries. Source is an explicitly
planned Knowledge Core entity. The API uses the current Space
CONCEPT_READ/CONCEPT_WRITE authority for the common guest/registered principal.
This feature adds no dependency, external terminology dataset or architecture
change.

Open **개인 출처 보관함 열기** in the selected Personal Space. The independent
**개인 출처 보관함** is available without selecting a Note or Concept. Use
**출처 추가** to register **제목**, **URL**, **인용 정보** and **라이선스 메모**.
For example, a website may have a URL and bibliographic citation; a printed book
may have a null URL and an author/publisher/year citation. **출처 편집** opens the
selected reference, **출처 수정 저장** changes its metadata, and **출처 수정 이력**
reads immutable snapshots. **출처 폐기** followed by **출처 폐기 확정** logically
retires the reference while retaining its identity and history.

The library uses actual 20-row UUIDv7 cursor pages. Displayed counts describe
loaded records rather than a total. Duplicate titles, URLs and citations are
allowed and remain distinguishable through immutable Source IDs. Source forms
are independent of Note, Concept, Alias and Claim drafts. No browser storage is
used for private reference data.

## Metadata and limits

Every Source has an immutable UUIDv7 sourceId and owning Space, independent
positive version and strong `"srcN"` ETag. Visibility is always PRIVATE. State is
ACTIVE or RETIRED. Source creation begins at version 1; each explicit update or
retirement increments only this Source's version. There is no Source count cap
or duplicate-metadata constraint.

All writes accept exactly four keys: title, url, citation and licenseNote.
Title uses Java `strip()` and must then contain 1 through 200 Unicode code points.
Citation preserves its exact submitted text, including newlines and outer
whitespace, with at most 4000 Unicode code points. License note is also preserved
exactly, with at most 1000 Unicode code points. Both may be empty, but either a
nonnull normalized URL or a nonblank Java-stripped citation is required. Every
string rejects NUL and unpaired surrogate characters.

URL may be null. A string is Java-stripped; an empty result becomes null.
Nonnull URLs must be ASCII, at most 2048 characters, and absolute HTTP or HTTPS
with a host. User information, whitespace, control characters and backslashes
are invalid. The port must be absent or from 1 through 65535; an explicitly empty
port is invalid. Authority must not contain percent signs, so encoded hosts and
IPv6 scope/zone identifiers are rejected. Fragments are allowed. The canonical
stored URL retains its submitted spelling after stripping;
the service does not infer equivalence between differently spelled URLs.

URL is metadata supplied by the user. Knowledge does not fetch pages, follow
redirects, import content or download attachments. **출처 URL 열기** is an explicit
browser navigation. Citation and license notes describe a reference; the platform
does not infer rights, source reliability, dataset redistribution permission or
verified medical authority from them. No external medical dataset is copied.

Claim-to-Source binding with pinned current revisions is implemented in
[claim-citations.md](claim-citations.md). Private Concept external mapping candidates
with pinned Source evidence are implemented in [external-mappings.md](external-mappings.md).
Arbitrary historical pin selection, verification transitions, public contribution/review,
reviewed mapping promotion, binary Source files,
graph and search projections are separate later scopes. Adding a Source does
not change any Claim or promote its DRAFT verification state. Existing Concept,
Alias and Claim identities, revisions and event contracts remain unchanged.

## API and lifecycle

The base path is `/api/spaces/{spaceId}/sources`.

| Operation | Result |
| --- | --- |
| GET base, `after`/`limit` | `{items: Source[], nextAfter: UUIDv7 or null}`; default 20, maximum 100 |
| POST base, `{title, url, citation, licenseNote}` | 201, Location and independent `"src1"` ETag |
| GET `/{sourceId}` | ACTIVE PRIVATE Source with `"srcN"` ETag |
| PUT `/{sourceId}`, same body, If-Match | Replaced metadata and incremented independent Source ETag |
| DELETE `/{sourceId}`, If-Match | Logical retirement, 204 and incremented Source ETag |
| GET `/{sourceId}/revisions`, `before`/`limit` | Immutable snapshots in descending version order; default 5, maximum 10 |

Canonical lowercase UUIDv7s identify each object and UUID cursor. Only known,
single-valued query keys are accepted. `after` and revision `before` are exclusive;
`before` defaults to Long.MAX_VALUE. GET of one Source and all writes accept no
query parameters. POST/PUT require application/json, at most 32768 body bytes,
exactly the documented four keys and valid Unicode text. Duplicate keys, trailing
tokens, unknown fields, invalid IDs/text/URLs and malformed cursors return 400.
Larger bodies return 413 and unsupported content types return 415.

Missing If-Match returns 428. Weak, wildcard, multiple, another aggregate's ETag
or signed 64-bit overflow returns 400. A stale Source version returns 412. A
version that cannot increment beyond Long.MAX_VALUE returns 409. Duplicate
metadata remains valid. Unknown, foreign, public or unavailable active Sources
return 404. Active reads omit retired Sources; known authorized Source history
remains readable after retirement. There is no destructive deletion or restore
operation.

Each request requires fresh Space and Identity authority before canonical lookup
or mutation. HTTPS is required. Writes require the exact configured browser
Origin and same-origin Sec-Fetch-Site when present. Responses use no-store.
Gateway allows only the canonical private Source collection, item and history
routes with bounded queries and bodies. OpenAPI:
[personal-sources.yaml](../contracts/openapi/personal-sources.yaml).

Unsaved edits require explicit conflict recovery after a 412. Inspecting the
latest canonical version must not overwrite a draft; restarting from it is a
separate action. A failed or uncertain mutation is not automatically repeated.
Known selected history survives Source retirement. Closing a detail or changing
Source/Space discards its unsaved Source form; sibling editors retain their own
state. AbortController, request generations and selected-ID checks reject late
responses after closing or changing views. Aborting an HTTP request cannot undo
a transaction already committed by Knowledge. Authority failures clear private
state rather than displaying another Space's metadata.

## Persistence and events

Knowledge Flyway V5 adds `knowledge_source` and `source_revision`. PostgreSQL
checks retain PRIVATE visibility, ACTIVE/RETIRED state, bounded metadata and
positive versions. `(space_id, source_id)` is unique for future same-service
relationship constraints. A partial active Space/Source-ID index supports cursor
reads. Revision identities and `(source_id, version)` are unique. Source revision
foreign keys remain inside Knowledge, with no cascading deletion or foreign
service table references.

Source changes lock their canonical row and recheck the expected version before
writing. Every successful create/update/retire atomically commits the Source,
immutable snapshot and transactional Outbox event. Validation errors, stale
versions and transaction failures leave no partial revision or event. Source
changes do not update Concept, Alias, Claim or Content reference rows.

`SourceCreated`, `SourceUpdated` and `SourceRetired` carry complete Protobuf
metadata snapshots through the existing Knowledge Debezium connector to
`knowledge.source.v1`, keyed by sourceId. The independent immutable artifact
`knowledge/source-events` version 1 uses VALIDITY FULL and COMPATIBILITY
FULL_TRANSITIVE. Existing Concept, Alias and Claim published schema bytes remain
unchanged.

The standard envelope includes stable eventId, type/version, producer, aggregate,
Space/actor/trace IDs and occurrence time. SourceChangedPayloadV1 includes
sourceId, title, URL, citation, licenseNote, revisionId, version, state and PRIVATE
visibility. Empty Protobuf URL means the canonical null URL. Consumers deduplicate
eventId and order snapshots by sourceId/version; they must apply Space authority
and lifecycle filtering before displaying private metadata. Kafka Connect has
literal-topic Write permission and the Knowledge validator literal-topic Read
permission. Event retention uses the same configured event partitions,
replication and seven-day delete policy as existing Knowledge topics.

Metrics use `brain.knowledge.source.changes{type=created|updated|retired}` and
`brain.knowledge.source.reads{type=list|get|history}`, alongside current Gateway
metrics, database readiness and trace propagation.

## Local delivery and validation

Apply brain-connect with local values, then wait for the Source topic and Kafka
users Ready. Run `scripts/Run-SourceContractValidation.py`; inspect its completed
Job and immutable-schema/rule checks. Build/import Knowledge and Gateway using
the existing local Kubernetes helpers, wait for all printed per-node import
Jobs, then use the Source installer. This preserves the current runtime,
database ownership and Outbox/CDC topology.

`scripts/Run-BackendValidation.py --knowledge-kafka` exercises the full reactor
with real PostgreSQL and Knowledge CDC/Kafka validation. Meaningful Source checks
cover strict bounded JSON/text/URLs, nullable/offline references, independent
ETags, Space isolation, duplicate metadata, exclusive cursor/history boundaries,
retirement, immutable revisions, atomic rollback, concurrent editors and complete
Kafka snapshot/key/schema/trace transport. Browser checks exercise actual Source
pages, metadata forms, stale-edit recovery, retirement/history, retained sibling
drafts, failures/retry, late responses, authorization and desktop/mobile layout.
Local validation evidence is recorded in
[personal-sources-validation.txt](personal-sources-validation.txt).

Reverse browsing from a selected private Source, with immutable Citation pins and
retained history: [source-citations.md](source-citations.md).

Retired Sources can now be rediscovered after closing or reloading the library;
see [retired Source archive](retired-sources.md).

## Compare retained revisions

Personal Concept and Source histories now compare any loaded earlier/later pair,
including retired archives, with exact fields and audit metadata. See
[knowledge-revision-comparison.md](knowledge-revision-comparison.md).
