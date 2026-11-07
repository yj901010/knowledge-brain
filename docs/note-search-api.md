# Private user Note search API

Implemented 2026-10-07 through Brain Query and the common HTTPS Gateway.

```http
GET /api/spaces/{spaceId}/search/notes?q=Kafka&offset=0&limit=20
Cookie: __Host-brain_guest=<existing opaque browser credential>
```

Use the same guest or registered session and Personal Space as the existing Note APIs. Responses
are no-store JSON with `total`, `offset`, `limit`, nullable `nextOffset`, and `items`
containing only `noteId` and `title`. Search matches plain title/Markdown text and
never returns raw Markdown, snippets, backend query DSL, scores or other Space data.
The separate Content API remains authoritative for reading/editing a selected note.
The route supports GET only. All three services are deployed locally.

## Authorization and scope

Brain Query validates HTTPS, UUIDv7, one common session cookie, query and page bounds. It calls
Space's separate `/internal/space/query-authorization` on EVERY request before any
OpenSearch request, including the count. Space resolves the current guest or registered principal through
Identity and checks current membership in its own PostgreSQL. The dedicated service
key permits NOTE_SEARCH decisions only; it cannot request NOTE_WRITE or MEDIA_WRITE.
Private PERSONAL Spaces support both guest and registered principals, including
claimed Spaces and selection among multiple owned personal Spaces. Other Space
types remain outside this endpoint.

No caller principal/bearer header is trusted. Gateway strips delegation headers and
allows only the declared search route and q/offset/limit parameters. Duplicate or
unknown parameters are rejected. Query service defaults remain disabled outside
explicitly configured deployments. Internal authorization routes stay absent from
Gateway. The restricted NetworkPolicy allows Brain Query to contact Space's API.

After permission is granted, OpenSearch applies Space + PRIVATE + ACTIVE filters
inside the query to both hits and exact totals. Its reader mTLS identity is separate
from the projector identity and does not have indexing/admin permissions. Malformed,
timed-out or partially failed storage responses are rejected, not returned as partial
results. Space/Identity/storage outages fail closed. Permission is not cached.

## Limits and consistency

- q: nonblank plain text, at most 200 Unicode code points, no control characters.
  Query operators, index names and arbitrary upstream URLs are not accepted as API
  parameters; q itself is analyzed plain text rather than interpreted query DSL.
- limit: 1..50 (default 20); offset: nonnegative (default 0); offset + limit <= 1000.
  nextOffset is supplied only when a full next page fits that window. Counts may
  exceed the result window, but belong exclusively to the authorized Space.
- Results sort by score and then immutable Note ID. Offset pages can shift under
  concurrent writes; this endpoint does not promise a point-in-time cursor.
- Projection is eventually consistent with Content writes. Canonical updates and
  retirement reach search through Outbox, Debezium, Kafka and Brain Query.
- Standard analysis is the current baseline. Dedicated Korean morphology, Tag/
  Properties filters, snippets, Graph View and Note-to-Concept links are not included.

Missing/invalid/revoked sessions receive 401; an inaccessible Space receives 404;
invalid input receives 400; unavailable authority/storage receives 503 with a generic
error body and no counts/hits. Gateway transport errors use its existing 502/504
contract. Query text, private titles, credentials and Space/principal IDs are not
metric labels. Search counters use bounded outcome tags and a duration timer. Trace
IDs propagate through Gateway, Brain Query, Space and Identity.

## Deployment and validation

Provision `brain-query-space-authorization` with the existing
`Provision-GuestApiSecrets.py`, then build/import Space, Brain Query and Gateway
images on both local nodes and install their shared/local Helm values. Brain Query
mounts its reader certificate and public Space trust anchor in addition to existing
projector/Kafka/API TLS credentials. No new library, foreign database access,
migration, event version, AI functionality or external search port is introduced.

```powershell
python .\scripts\Run-NoteSearchValidation.py
```

Run `python .\scripts\Run-NoteSearchFailureValidation.py` for coordinated actual
Space/OpenSearch Pod replacement with original replica counts restored even on
failure. The underlying `--failures` browser mode uses operator handshake markers. Its temporary browser test Job receives
only public HTTPS trust, without DB passwords, Kafka identities or search/admin
credentials. Cookies stay in process memory. Evidence is recorded in
`note-search-api-validation.txt`; the search and Space authorization OpenAPI
contracts are in `contracts/openapi/brain-note-search.yaml` and
`contracts/openapi/space-query-authorization.yaml`. Gateway's contract declares the
same public operation.
