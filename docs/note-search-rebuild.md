# Canonical note search rebuild

Implemented 2026-10-07. Content owns a restricted HTTPS GET
`/internal/content/note-snapshots` contract, documented in
`contracts/openapi/content-snapshot.yaml`. It reads only Content's own Note and
current NoteRevision, with UUID keyset pages of at most five notes. It includes
PRIVATE ACTIVE snapshots and RETIRED versioned tombstones with empty title/body.
The upper UUID remains fixed across pages. This is not a transaction-wide snapshot;
concurrent changes and clock-skewed IDs are covered by the Kafka tail.

A separate random service credential is stored in `brain-content-search-snapshot`
in brain-apps. It is mounted only by Content and temporary recovery Jobs. Guest
cookies are rejected even with this credential. Gateway has no internal route.
Brain Query's normal deployment receives neither this credential nor search admin
certificates. Network policy permits only the dedicated recovery Pod label for
this additional path; recovery Pods do not become Brain Query Service endpoints.

## Local recovery

```powershell
python .\scripts\Provision-NoteSnapshotSecret.py
python .\scripts\Rebuild-LocalNoteSearch.py
```

Content and Brain Query must first be built, imported into both local nodes and
installed with the current chart/values. Provision the scoped secret before
installing Content's enabled snapshot API. The recovery script checks the local
k3d context and the authenticated Content API before changing derived state.

1. Preserve the consumer replica count; scale Brain Query to zero and wait for all
   Deployment consumer Pods to disappear. Canonical writes stay available.
2. A separate admin Job deletes and recreates only `brain-notes-v1` with the strict
   mapping. It does not modify canonical data, service databases or PVCs.
3. A confined Brain Query Job with its existing read-only Kafka identity and
   fixed-index projector mTLS captures current partition end offsets BEFORE
   scanning canonical Content pages. No foreign SQL or fabricated domain event
   is used. Canonical note versions protect against duplicates and older writes.
4. Replay actual validated Protobuf events from those offsets through partition
   end offsets captured after the scan. Commit the durable group checkpoint only
   after every snapshot and replayed revision has been accepted or verified.
5. Restore the saved replica count and wait for live consumer readiness. Delayed
   CDC events after that checkpoint are processed by the ordinary consumer.

If reset/rebuild fails, consumers remain paused. Inspect the named Job and retry
this script after fixing the cause; it uses the saved replica count from
`.local/note-search-rebuild-state.json`. Do not manually resume a partially rebuilt
index. A running recovery Job blocks another invocation. The local script is a
single-operator maintenance tool, not a distributed production recovery controller.
Recovery is eventually consistent; there is a temporary search availability gap.
The 7-day Kafka retention does not limit canonical note recovery. An offset falling
out of retention during a long scan fails rather than silently skipping events.

Derived Inbox/queue remains owned by Brain Query; it is not a canonical backup.
Canonical snapshots rebuild the index even if old events have expired. Any pending
ordinary queue entry is replayed after restart under the same version checks.
No new dependency, database migration or breaking event contract was introduced.

## Evidence

See `note-search-rebuild-validation.txt`. Full regression: 148 tests, no failures,
errors or skips. Runtime exercises real authenticated HTTPS snapshots, index reset,
concurrent authorized create/update/retire, Kafka checkpoint and consumer restart.
End-user Space-authorized search routing is now implemented in `note-search-api.md`;
graph and Note-to-Concept links remain separate work.
