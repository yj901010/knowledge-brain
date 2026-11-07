# Note tags and typed properties

Content owns Space-scoped canonical tags, property definitions and note assignments.
Existing Space authorization guards all reads and writes. Data remains PRIVATE.
No cross-service SQL or new dependency is introduced.

GET /api/spaces/{spaceId}/notes/{noteId}/metadata returns tags and properties with
an independent ETag `"m0"` for empty initial metadata. PUT replaces both complete
sets and requires this ETag in If-Match. Missing is 428, malformed is 400, stale
is 412. Browser writes require the configured HTTPS Origin. Markdown edits retain
metadata and its version; metadata edits retain Markdown and its version.

```json
{"tags":["study"],"properties":{"score":{"type":"NUMBER","value":3.125},"done":{"type":"BOOLEAN","value":true}}}
```

Names are NFC-normalized and trimmed, case-sensitive, 1–64 Unicode code points.
At most 32 tags and 32 properties; JSON body at most 16 KiB. Duplicate normalized
names, duplicate JSON keys, trailing JSON, unknown fields and null values fail.
STRING allows 1000 code points; NUMBER preserves decimal precision up to 38 digits
and absolute scale 18; BOOLEAN requires JSON true/false; DATE requires a valid
YYYY-MM-DD calendar date; STRING_LIST allows 20 strings of at most 200 code points.
Strings reject NUL. Property types are fixed within a Space; changing type returns
409 and rolls back the entire replacement. Remove an assignment by omitting it.
Tags and definitions keep UUIDv7 IDs across reuse and are isolated between Spaces.

GET /api/spaces/{spaceId}/tags and /property-definitions list UUID keyset pages
(default 20, maximum 100; exclusive after cursor), with activeNoteCount. Definitions
remain available with count zero after clearing or retiring notes. Definitions have
no independent rename/delete API. Canonical [tag-to-Note browsing](tag-notes.md) and
[property-to-Note/value browsing](property-notes.md), including
[exact property value conditions](property-filter.md) and [numeric/date ranges](property-range.md),
are available. Metadata value
filters in full-text search remain separate future work.

GET note /metadata/revisions returns immutable snapshots newest first, default 5,
maximum 10, exclusive before metadata version. Retirement preserves assignments
and private history, excludes usage counts, and emits a retirement metadata snapshot.
Existing notes start at metadata version zero without fabricated revision history.

Assignment changes, metadata revision and raw Protobuf Outbox event commit in one
Content transaction. Debezium publishes content.note-metadata.v1, schema artifact
content/note-metadata-events version 1. Events contain full tag/property snapshots,
note version/state, actor, trace and stable revision/event IDs. Consumers must dedupe
stable event IDs and apply metadata versions in order. Existing Note and Wiki Link
contracts remain unchanged. Metadata search/graph projections remain planned. The selected Note metadata
editor is now documented in note-metadata-ui.md.

Validated on local Kubernetes with the full 100-test suite, actual PostgreSQL and
Kafka, common-origin HTTPS and Content Pod replacement. Evidence:
[note-metadata-runtime-validation.txt](note-metadata-runtime-validation.txt).

Private immutable tag/property activity history is now available: [metadata activity](metadata-activity.md).
