# Daily Note and Journal API

Daily Note save/change/archive events now also appear in the [private Activity timeline](note-activity.md). The Content Journal remains the canonical date-based view.

Content owns the calendar-date binding and Journal text. Space remains the access
control authority. Daily Notes are ordinary PRIVATE UUIDv7 Notes: edit Markdown,
retire, read revisions, use Wiki Links/backlinks, tags and typed properties through
the existing Note APIs. Titles do not determine or change calendar-date identity.
The Content Journal is a date-ordered single-domain view; combined Activity timelines
remain Activity-owned future projections. No cross-service SQL, new dependency or AI.

POST `/api/spaces/{spaceId}/daily-notes` with the complete <=1 KiB JSON body:

```json
{"date":"2026-10-07","timezone":"Asia/Seoul"}
```

Creates one Note per Space/date, with date title and `# YYYY-MM-DD` Markdown heading,
and returns 201, canonical Note Location and its strong ETag. Reopening returns 200
and current Note unchanged, even after editing its title, body, tags or properties.
Database uniqueness plus the existing Space transaction lock ensures concurrent
requests cannot create duplicate Notes, revisions, bindings or events. Browser writes
require configured same-origin HTTPS and NOTE_WRITE permission.

Date is a valid calendar date from 0001-01-01 to 9999-12-31, exact YYYY-MM-DD.
Timezone is an explicit Java TZDB/IANA ZoneId (including UTC), maximum 100 characters;
unknown IDs and fixed-offset strings such as +09:00 are rejected. Space/date identity
is independent of timezone, so travel cannot silently duplicate the same date.
First creation stores an immutable timezone. Reopening the same date using a different
ID returns 409. Aliases are not silently rewritten. No inferred machine/browser
zone, automatic date shifting, mutable Space timezone policy or scheduler is added.

GET `/daily-notes/today?timezone=Asia%2FSeoul` computes the local date from the
server Clock instant and requested timezone. It is read-only and returns date,
timezone and optional existing entry. An existing entry retains its creation timezone;
absent/retired entries are null. It never creates a Note. Clients can explicitly POST
the returned date, while respecting an existing entry's original timezone.

GET `/daily-notes/{date}` returns the current Note and immutable binding. Missing
or retired entries return 404. GET `/daily-notes` lists active Journal summaries by
descending date. from/to are inclusive; before is an exclusive date cursor. Default
limit 20, maximum 100; nextBefore is null at the end. Ordinary Notes are excluded.
Every read requires NOTE_READ authority and uses no-store. Editing requires the
existing Note If-Match; GET/open ETags correspond to that Note version. No direct
PUT/DELETE daily-binding routes exist. The migration forbids binding update/deletion.

Retiring the ordinary Note hides it from Daily reads/Journal, preserves binding and
private revision history, and returns 409 on attempted recreation of that date.
Restore/reassignment is deliberately not implemented. Registered identity claiming
can preserve the same Space, date and Note IDs.

Creation commits Note, immutable binding, Note/link revisions and all Outbox events
atomically. Normal Note edits/retirement emit DailyNoteChanged in the same transaction.
Debezium publishes private `content.daily-note.v1`, Registry artifact
`content/daily-note-events` v1. Payload includes Note ID/version/state/title/visibility,
calendar date and original timezone; envelope carries actor, Space, event and trace IDs.
Body changes remain available in existing Note events, metadata in metadata events.
Reopening produces no event; metadata-only changes keep Daily/Note version unchanged.
Consumers must dedupe stable IDs and apply per-Note versions in order; projections
can combine the existing events inside their own owned read storage. Existing event
contracts stay immutable. This API milestone did not add an Activity consumer or frontend. Subsequent Note editor, Graph/Search and Journal UI milestones are documented separately.

Errors include invalid JSON/date/zone 400, missing credentials 401, invalid Origin
403, unauthorized Space 404, immutable-zone/retired conflict 409, body too large 413,
non-JSON 415 and unavailable authorization dependency 503. Unknown fields, duplicate
JSON keys and trailing JSON are rejected; failed transactions reserve no date.

Validated in local Kubernetes with 113 passing tests, real PostgreSQL/Kafka,
common-origin HTTPS and verified replacement Pod readiness. Evidence:
[daily-notes-runtime-validation.txt](daily-notes-runtime-validation.txt).

## Daily Note / Journal frontend

The Personal Graph Daily Note / Journal card now exposes these canonical APIs.
See [note-journal-ui.md](note-journal-ui.md) for explicit timezone, server today,
existing Note preservation, date filters, paging and authorization behavior.
This is the Content date-based Journal; the combined Activity timeline remains future work.
