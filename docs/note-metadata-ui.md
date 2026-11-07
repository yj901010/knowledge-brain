# Note tags and typed properties UI

The selected Note inspector now shows and edits canonical tags and properties
through existing Content metadata GET/PUT. Tags can be added/removed. Property
rows support STRING, NUMBER, BOOLEAN, DATE and STRING_LIST. Lists use separate
text fields, preserving embedded newlines and empty strings. Removing a row omits
its assignment; it does not delete the Space definition. Assigned property names
and types stay fixed; new names colliding with a different Space type receive 409.

A fresh GET supplies the exact independent metadata ETag (including m0). Save
sends full sets with If-Match. On 412 the draft remains and saving is blocked;
explicit restart discards the draft and reloads canonical values plus ETag. Failed
writes preserve the draft and old ETag, so uncertain successful writes cannot be
silently repeated with a refreshed version. Cancel performs no write. Canonical
Markdown/Note revision and metadata revision remain independent.

Numbers remain decimal token strings in UI state. The JSON reader validates the
original grammar, then preserves numeric tokens without float conversion; the
serializer validates number grammar and emits exact number tokens. Existing
38-digit, scale-18 values survive unrelated tag edits without rounding. Standard
JSON escaping protects strings/names. The server validates calendar dates, decimal
precision/scale, fixed Space types and all canonical limits. Client checks names,
normalized duplicates, counts, strings/list limits and 16 KiB UTF-8 body size.

All data and drafts stay in memory. Note/Space changes, private-view clearing,
hiding and logout unmount this component and invalidate/abort pending operations.
401/403/404 clears private state and invokes the existing parent authority refresh.
Content metadata decoding now uses BigDecimal, matching existing canonical decimal
precision rules. A real PostgreSQL regression reproduced the old rounding in reads
and checks current metadata, immutable snapshots and Outbox payloads after the fix.
Past snapshots/events already rounded are not rewritten or reconstructed. No
schema/event contract/dependency or architecture change is introduced. Existing
Content request counters, traces and metadata Outbox remain in use. No autosave,
history restoration, tag-filtered search or graph recoloring is added.

Build/import Content and Gateway on both local nodes and run Install-LocalGraphMetadata.py
with the existing Helm executable. Run Run-GraphWebValidation.py --public; metadata
regression covers all types, exact decimal roundtrip, real conflict, Space type
collision, failure/retry, cancel, privacy and mobile with existing UI suites.
See note-metadata-ui-validation.txt for delivery evidence.

Windows builds now use the same pinned Next export in the existing local Linux
Playwright tooling image after repeated native EPERM mkdir failures. CSP hashes
and publisher notices are generated from that export; no Next server is deployed.
The build Job uses no credentials or service-account token, an owned source
ConfigMap, bounded resources/deadline and the existing validation TTL.

The Windows build command now requires the existing local Kubernetes context;
Linux hosts retain native npm build. Export artifacts are kept under ignored
.local/graph-static-build-* for inspection, with successful Job metadata in
.local/graph-static-build.json. Failed native scratch artifacts remain ignored.

The local browser test configuration limits workers to two: six concurrent browser
workers exceeded the existing 1536 MiB validation Pod limit (OOMKilled). This keeps
test resource use bounded without changing service memory or application behavior.

Space의 태그별 노트 목록은 [태그별 노트 탐색](tag-notes.md)에서 연다.

노트별 속성값을 함께 보려면 [속성별 노트 탐색](property-notes.md)을 사용한다.
