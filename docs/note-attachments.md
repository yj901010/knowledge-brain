# Private Note attachments and Media

Architecture check: AGENTS.md sections 5/12/16 and PROJECT.md sections 12/16 remain authoritative. Media owns metadata and upload lifecycle in brain_media; MinIO owns private binary objects. Content owns Note-to-Media UUIDv7 relations and their revision history in brain_content. There are no cross-service SQL queries, joins, foreign keys or distributed database transactions. Personal data remains PRIVATE. No AI functionality is introduced.

## Usage

Keep the common HTTPS Gateway forward running, and start the direct object-storage forward in a second terminal:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-LocalGateway.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-LocalMedia.ps1
```

Trust `.local/guest-api-ca.crt` for both localhost:8443 and localhost:9443. The original milestone provided backend APIs. The Note attachment UI is now documented in [note-attachments-ui.md](note-attachments-ui.md). Browser identity uses the existing secure guest cookie at the Gateway. MinIO receives scoped signed capabilities and does not receive the guest cookie.

1. POST `/api/spaces/{spaceId}/media` with JSON `{ "filename": "reading.pdf", "mimeType": "application/pdf", "byteSize": 1234, "sha256": "<64 lowercase hexadecimal characters>" }`. It returns 201, PENDING metadata and an upload form.
2. Submit multipart POST directly to the returned upload.url: every returned field followed by the `file` part. Do not use PUT. The signature restricts the exact staging key, declared MIME and exact file byte size; expires after 300 seconds.
3. POST `/api/spaces/{spaceId}/media/{mediaId}/complete` with `{}`. Media streams the uploaded immutable staging version, verifies byte count/MIME/SHA-256, copies it to a sealed object and stores that object's version. Completion returns AVAILABLE/version 2 and is idempotent. Wrong bytes return 400; missing bytes return 409. POST `.../upload-form` with `{}` renews a pending upload form without creating another allocation.
4. GET `/api/spaces/{spaceId}/notes/{noteId}/attachments` returns an independent strong ETag such as `"a0"`. PUT the complete ordered list `{ "mediaIds": ["<available media UUIDv7>"] }` with that If-Match and same HTTPS Origin. Maximum 20 unique files, all AVAILABLE and in the authorized Space. Retained media keeps its attachment ID; reordered relations retain identity. Daily Notes use the same Note attachment API.
5. GET `.../attachments/revisions?before=3&limit=5` returns descending immutable snapshots. Missing If-Match is 428, stale is 412. Markdown and metadata versions do not change when attachments change, and ordinary Note edits retain attachments. Retiring a Note clears its active relations and records an empty snapshot while preserving history. It never deletes a shared Media object.
6. POST `/api/spaces/{spaceId}/media/{mediaId}/download` with `{}` returns a 60-second signed GET for the sealed object version. Responses force `attachment` disposition and `application/octet-stream`. A still-valid old upload form can affect staging only, not published bytes. GET Media lists use UUIDv7 after/limit paging (default 20, maximum 100).

Guest limits are 10 MiB per allocation, 100 MiB allocated per Space and 10 pending uploads. Supported declared MIME values are PDF, PNG, JPEG, GIF, WebP and application/octet-stream. MIME is declared metadata, not content sniffing or malware scanning; downloads are always forced attachments.

## Authorization and events

Every external API rechecks authoritative Space authorization through HTTPS. Separate least-privilege credentials protect Space-to-Media decisions and Content-to-Media AVAILABLE checks. Caller-provided identity/service headers are not trusted. Missing/revoked identity is 401, cross-Space resources are 404, wrong Origin is 403, unavailable authority/storage is 503. Failure cannot silently authorize or change Content relations. All metadata/capability responses use no-store; signed URLs and credentials must not be logged.

Local ACID Outbox records are delivered through restricted Debezium roles to `media.file.v1` and `content.note-attachments.v1`. The contracts are `media/file-events` v1 and `content/note-attachments-events` v1, registered immutably in Apicurio. Payloads include Space/actor/trace, aggregate versions and complete attachment snapshots. Binary objects and storage credentials are excluded. Future consumers must handle at-least-once delivery idempotently.

## Current scope and recovery

There is no Media deletion/retirement or storage quota reclamation API yet. Removing a Note attachment does not reclaim a Media allocation. Pending uploads remain resumable. Staging/noncurrent object garbage collection, orphan reconciliation after a copy succeeds but the local DB commit fails, file variants/thumbnails, audio and scanning remain subsequent work. The basic attachment frontend is now available. The 100 MiB quota limits metadata allocations, not physical versioned MinIO bytes; replayed staging versions may consume extra storage. A future garbage collector must never blindly expire sealed versions referenced by AVAILABLE records.

MinIO uses a persistent 10 GiB local PVC, TLS, private/versioned brain-media bucket, a dedicated scoped service account, and no exposed console. Root credentials stay in brain-data and are absent from application Pods. The standalone source-built server and Java SDK license/version records are in [media-storage.md](media-storage.md) and [media-dependency-review.txt](media-dependency-review.txt). Local single-machine persistence is not a backup/HA guarantee.

## Validation

See [attachments-runtime-validation.txt](attachments-runtime-validation.txt) for exact image revisions, Jobs, contract hashes and replacement evidence. Unit/PostgreSQL tests cover quota concurrency, immutable completion, tenant isolation, attachment stable IDs, independent versions, write conflicts, atomic Outbox rollback, private history and authorization failure. Real TLS MinIO tests cover policy bounds, invalid checksums, anonymous denial and replay isolation. Kafka tests compare raw received Protobuf bytes and schema/trace headers with their committed Outbox records.

OpenAPI: [media-files.yaml](../contracts/openapi/media-files.yaml), [content-notes.yaml](../contracts/openapi/content-notes.yaml), [gateway-guest.yaml](../contracts/openapi/gateway-guest.yaml).
