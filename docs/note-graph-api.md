# Private Note Graph API and Graph View

GET /api/spaces/{UUIDv7}/graph/notes accepts only an optional single limit=1..200 (default 100). Its response contains nodes (noteId,title), resolved directed links (linkId,sourceNoteId,targetNoteId), and truncated. Bounds are 200 nodes and 1,000 links; no Markdown or global counts are returned. Brain Query reads its existing live JanusGraph session using the restricted projector role, never another service database.

Each request first validates the secure guest cookie through fresh Space NOTE_GRAPH authorization and Identity session validation. Space checks its own PERSONAL PRIVATE ownership. Caller principal headers cannot grant access. Missing/revoked sessions, foreign Spaces, authorization outages and graph storage failures return generic errors without graph data; responses use no-store. NOTE_SEARCH remains independent and cannot authorize graph reads.

## Graph View

Open https://localhost:8443/graph after starting the common HTTPS Gateway:

```powershell
powershell -ExecutionPolicy Bypass -File C:\dev\knowledge-brain\scripts\Start-LocalGateway.ps1
```

The existing public local CA is .local/guest-api-ca.crt; use the established local trust setup. The host forward occupies its terminal until Ctrl+C. Development, stage and production serve the same Next.js static-export artifact through Gateway; no Next server or image optimizer is deployed. Build Gateway through scripts/Build-LocalSpaceImage.py so scripts/Build-GraphWeb.py builds the pinned frontend and embeds it before Maven packaging.

The initial page offers guest Personal Space creation or restores an existing session. Users can create private Markdown notes, resolve [[Wiki Links]], inspect connected note bodies, filter visible titles, pan/zoom and use keyboard node activation. Links reflect asynchronous canonical Content events. Graph refreshes every 15 seconds and on focus; hidden pages, failed reads and denied authority clear private graph/detail state. No browser storage is used for note data. Graph truncation is visible; the UI does not imply a global graph count.

React renders titles and Markdown as text. Generated inline Next bootstrap scripts use exact CSP hashes, with same-origin static resources and APIs, no unsafe-inline/eval, and frame-ancestors none. Gateway permits only the declared GET API/static paths. Graph responses have a separate 1 MiB transport cap; existing API caps remain bounded.

## Validation

```powershell
python scripts/Run-BackendValidation.py --graph-storage --search-storage --content-kafka --space-kafka --media-storage --media-kafka --cdc
python scripts/Run-NoteGraphApiValidation.py
python scripts/Run-NoteGraphApiFailureValidation.py
python scripts/Run-GraphWebValidation.py
```

The fault coordinator creates its own --failures Job by default; --job can attach an already running owned fault-validation Job. It restores original replica counts and verifies changed Space/Cassandra Pod UIDs; it retains the Cassandra PVC.

The confined Playwright Job targets the actual deployed HTTPS Gateway through a Pod-local TCP tunnel so browser Origin matches localhost:8443. It mounts only the public CA and imports that CA into the test browser trust store. It does not bypass TLS or change host trust/firewall settings, mount Kubernetes credentials, or read production data; its fresh guest fixtures are isolated and revoked at test completion. Browser dependency setup runs only in the disposable test container. Desktop/mobile screenshots and sanitized logs are saved in .local/graph-ui-results.

Direct dependencies are pinned with package-lock.json. Next.js/React MIT and TypeScript/Playwright Apache-2.0 license records are retained in infra/dependencies/graph-web.json and apps/graph-web/THIRD-PARTY-NOTICES.txt. Optional native image build libraries are not copied into Gateway/browser runtime.

Evidence: [note-graph-api-validation.txt](note-graph-api-validation.txt). This milestone implements private Note-to-Note Graph View. Private Note-to-Concept references are implemented in [note-concepts.md](note-concepts.md); public references, registered claiming and Activity event timelines remain planned.
