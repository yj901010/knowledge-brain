# Wiki Links and Backlinks

Content now owns Wiki link parsing, stable Note bindings, unresolved references,
backlinks and versioned link snapshots. This extends private Markdown Notes.
Existing service boundaries, Space authorization and Note concurrency remain intact.
No AI/Markdown rendering library, external dataset or additional runtime dependency
was introduced. Subsequent delivery includes metadata, Daily Notes, attachments,
search/graph, Concept links and the editor; see personal-notes-roadmap.md.
Canonical link/backlink navigation UI is documented in note-links-ui.md.

## Syntax and parsing rules

| Syntax | Meaning |
| --- | --- |
| `[[Title]]` | Same-Space active Note title reference |
| `[[Title|display label]]` | Reference with display label |
| `[[Title#section|label]]` | Stores an optional fragment; section existence is not validated |
| `[[note:<canonical UUIDv7>|label]]` | Explicit Note ID reference, restricted to the same Space |

Title matching strips surrounding Unicode whitespace and normalizes NFC, with
case-sensitive equality. Note titles remain exactly stored; keys are not a rename
of the note. Repeated occurrences are separate links. Link IDs use UUIDv7; returned
start/end offsets are inclusive/exclusive Java/JavaScript UTF-16 code-unit offsets.
At most 256 recognized Wiki Links per Note. Alias/fragment/target are each limited
to 200 Unicode code points. Unsupported/malformed forms remain literal text.

This deterministic scanner supports backslash escapes, exact-run inline backtick
code spans (including multiple lines), backtick/tilde fences with at most 3 leading
spaces, four-space/tab indented lines, and HTML comments. Links in these excluded
regions are not recognized. An unclosed fence/comment excludes the remainder;
an unmatched inline backtick is literal. This is a documented Wiki syntax subset,
not a full CommonMark renderer: list indentation semantics, embedded files and
arbitrary HTML blocks are not interpreted. Raw HTML remains stored text, as before.
The Markdown renderer/editor is still future work.

## Binding, ambiguity and lifecycle

A title with zero same-Space active candidates is UNRESOLVED; two or more is
AMBIGUOUS. The service does not choose the first matching title. An explicit Note ID
can disambiguate by editing the source Markdown using its ordinary If-Match guard.
Unknown and other-Space IDs both remain unresolved with no target title/ID disclosed.
There is no cross-Space lookup, link resolution or backlink count.

Once resolved, the immutable target Note ID persists across target renames and
title reuse. Source Markdown is not rewritten. When saving a source, unchanged
normalized targets retain their binding and link ID in occurrence order; offsets,
alias and fragment can change. Removing and later adding the reference makes a
new link. Retarget explicitly by changing its target token/ID. Identical repeated
tokens are matched by occurrence order, not by an inferred semantic identity.

New/renamed/retired targets reconsider indexed unresolved/ambiguous references
affected by that title or ID. Becoming uniquely resolvable binds the reference
without changing source Markdown, Note version or ordinary Note revision history.
Each such resolution has its own monotonically increasing linkVersion and durable
link revision. Adding another duplicate title does not invalidate an existing
stable ID binding. Self-links are allowed.

Retiring a target preserves existing bound IDs with TARGET_RETIRED status; title
reuse never moves them to a new Note. Retiring a source removes its active outgoing
edges and produces an empty links snapshot. Unlinking/removing source references
also removes corresponding backlinks. Retired Notes are 404 for these active APIs;
existing authorized Note history remains available. Current outgoing metadata
includes the current same-Space target title, even for a bound retired target.

## Private read APIs

Both endpoints reuse verified guest or registered common session credentials and Space NOTE_READ authorization
on every request. Missing/expired/revoked cookies return 401; another Space, unknown
or retired Note returns 404. Caller principal/user headers confer no access.
No permission cache, public graph projection or title/count leak is introduced.

| GET path | Response/pagination |
| --- | --- |
| `/api/spaces/{spaceId}/notes/{noteId}/links` | noteVersion, linkVersion, items, nextAfter; occurrence order, exclusive integer ordinal after |
| `/api/spaces/{spaceId}/notes/{noteId}/backlinks` | items of active sourceNoteId/title/version/linkCount and nextAfter; UUID order, exclusive UUID after |

limit is 1..100, default 20. Repeated references count toward linkCount but backlink
items are grouped by source Note. Link metadata carries ID, offsets, token,
alias/fragment, bound target ID/title or null, and status. It does not return source
Markdown. Both prohibit caching; use linkVersion for link snapshots because Note
version alone does not capture target-triggered resolution. These routes are GET
only at Gateway and allow only declared after/limit query parameters. No separate
mutation endpoint or cross-domain distributed join is added.

Contracts are in the composed Gateway and Content OpenAPI files. Existing Note
mutation/revision endpoints and strict ETag checks are unchanged.

## Canonical transactions and events

Flyway V2 adds title_key, link_version and links_dirty to Note plus Content-owned
note_link and note_link_revision. Source and bound target composite foreign keys
enforce same-Space references within Content's own DB. No Space/Identity database
is queried. Canonical Note/revision, outgoing bindings, dependent resolution changes,
durable full link snapshots and all Outbox records commit in one local transaction.
Any failure rolls the entire mutation/resolution back. Space-keyed advisory locks
serialize Content writes/link reads so opposite references cannot deadlock by
locking source/target in opposite orders. There is no claim of benchmarked throughput.

V2 marks existing/legacy Note writes dirty with a DB trigger. Startup backfill runs
before readiness, under Space and row locks. Note mutations/link reads also reconcile
dirty rows to support legacy catch-up, preserving old normalized title keys during
rename. Restart with clean rows creates no duplicate snapshots/events. Backfill
records the last Note revision's actor and a valid generated trace; it does not
change existing Note IDs/versions/text/history. Historical link bindings before this
feature cannot be inferred from old plain Markdown and are not invented.

Backfill validates the same parser bounds. A legacy Note exceeding the Wiki limit
causes the migration catch-up to fail transactionally; it is not silently truncated.
For large data volumes, assess and batch backfill before production rollout. Local
Content uses Recreate for this schema/feature cutover. Production initial deployment
must quiesce old writers or complete an explicit backfill/cutover procedure before
claiming strong metadata consistency with concurrent old binaries.

Existing immutable content/note-events v1 remains byte-for-byte unchanged. Separate
content_note_links_v1.proto and Apicurio content/note-links-events v1 carry stable
envelope fields 1..11 and NoteLinksChanged full snapshots. The payload includes
source ID/state, noteVersion, linkVersion, link revision ID and outgoing edges.
The envelope has stable event ID, Space, actor and propagated W3C trace ID.
Snapshot records are canonical audit data; Outbox is the delivery mechanism.

Existing content-outbox connector routes aggregatetype note-links to the new private
content.note-links.v1 topic; no direct Kafka producer or separate CDC service is
added. Connect Write/Describe ACL and validator Read/Describe ACL are restricted
to this precreated topic. The CDC role still reads only Content Outbox and cannot
read canonical Notes/links or connect to other owner DBs. Base/local partition and
replication rules match existing event topics. Registry validity FULL and
compatibility FULL_TRANSITIVE apply to the independent immutable artifact.

Future projectors must durably deduplicate event_id and apply increasing linkVersion
per source. Note and link topics have no global cross-topic ordering. The link
snapshot is complete, including empty snapshots for unlinks/retirement; do not
ignore target-triggered events just because Note version is unchanged. Graph/search
projectors remain rebuildable later work, not a substitute canonical store.

## Local rollout and checks

1. Register/verify schema with Run-WikiContractValidation.py.
2. Upgrade brain-connect chart with local values; wait topic/user Ready. Existing
   content-outbox connector needs no config change. Refresh the readonly validator
   secret with Provision-ContentValidationClient.py.
3. Run Run-BackendValidation.py --cdc --space-kafka --content-kafka for real
   PostgreSQL/Kafka tests. Build/import the Content and Gateway immutable local
   images on both nodes using the existing --service helpers.
4. Deploy Content and Gateway base + local values with actual imported image tags;
   wait both Ready before API validation. Run Run-WikiApiValidation.py, with
   optional --restart marker mode, plus Run-NotesApiValidation.py for regression.

The HTTPS runner waits rollout before creating fixtures; POSTs are not retried.
Only public trust CA is mounted, and browser credentials stay in verifier memory.
Temporary image artifact endpoints are removed after imports. Link read counters
brain.content.links.reads have outgoing/backlinks tags; existing pool/readiness,
request metrics and trace propagation remain. Full observability stack rollout is
still tracked independently. No host browser or frontend editor is claimed tested.

Evidence: wiki-links-runtime-validation.txt. If Gateway replacement ended the PC
port forward, rerun Start-LocalGateway.ps1 in the existing manner.
