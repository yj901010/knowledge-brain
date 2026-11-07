# Private Note attachment UI

The Note inspector now exposes attachment reads, ordered-list mutation, independent
a-ETag concurrency, immutable history and the existing Media lifecycle. Uploading a
file and linking it are explicit separate actions; completed files can be reused by
other Notes in the same Space. Removing a relation never deletes Media or frees its
allocation. Note Markdown and metadata versions remain independent. Daily Notes use
the same inspector. Maximum 20 attachments; file allocations retain the existing
10 MiB/file, 100 MiB/Space and ten pending upload limits.

A browser file selection is held only in memory. SHA-256 is computed from its bytes.
Unknown browser MIME types use application/octet-stream. A scoped multipart form
uploads bytes directly to MinIO with credentials omitted and no referrer, then Media
verifies and seals the version. The UI does not automatically link the file. Failed
transfers retain the allocation ID for explicit renewed-form retry; a library PENDING
row can also select an existing allocation for retry or idempotent completion check.
Resume requires the same normalized filename, MIME, byte count and digest. Uncertain
allocation responses require checking the library before allocating again. Clearing
the upload selection does not delete pending allocations. No cancellation/deletion
API, quota reclamation, malware scanning or inline preview is claimed.

Downloads obtain a fresh signed capability and fetch directly without credentials;
the verified-size blob is offered as a transient browser download, then its object
URL is revoked. Signed forms/URLs remain local request variables, never persistent
state, HTML links, storage or logs. Browser transfers accept only HTTPS URLs at the
explicit configured origin. /graph/media-config returns only that public origin with
no-store; BRAIN_GATEWAY_MEDIA_PUBLIC_ORIGIN is environment-specific, empty disables
transfers. Gateway validates a bare HTTPS origin and adds only that origin to the
existing connect-src policy. Other CSP directives remain unchanged. Local value:
https://localhost:9443. Domain API, schemas and V1 event contracts remain unchanged.

Fresh Content/Media/Space authorization is preserved. 412 stops attachment writes
until the user explicitly reloads the authoritative list. Failed list/history reads
clear the relevant rows. 401/403/404 clears the entire private view. File/metadata state
resets on Note/Space/hide/logout and stale responses cannot reopen a replaced view.
History pages five revisions; Space Media pages twenty records with its UUID cursor.
Loaded counts describe displayed records, not a total or storage usage.

Build/import Gateway on both nodes and run Install-LocalGraphAttachments.py --helm
with the existing Helm executable. Browser validation uses the existing trusted CA
and a raw TLS storage bridge; Run-GraphWebValidation.py --public includes attachment
coverage alongside existing regressions. --attachments-only focuses on this fixture.
Evidence: note-attachments-ui-validation.txt.

For local interactive use keep Start-LocalGateway.ps1 running and open another
terminal for:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-LocalMedia.ps1
```

Trust .local/guest-api-ca.crt for localhost:8443 and localhost:9443. The storage forward
is required for uploads/downloads; the existing attachment/library API reads use the
Gateway. The UI remains the functional layout pending the agreed later redesign.
