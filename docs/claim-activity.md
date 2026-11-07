# Private Claim activity timeline

Open `/graph` → **활동 열기** → **활동 종류: 개념 관계**. Create, edit and retire
personal Concept relationships in the existing editor. After eventual projection,
the timeline shows one activity per Claim revision, with date/timezone filters,
20-row UI pages, event-time ordering and explicit current lookup.

AGENTS.md and PROJECT.md were reviewed first. Knowledge remains the canonical
owner of Claims, revisions and Outbox. Activity owns only its Inbox and derived
ClickHouse view. Space retains fresh authorization on each private page. Guests
and registered users use their existing Personal Spaces. No new dependency,
dataset, publication, verification policy or cross-service database access.

## Frozen meaning and current lookup

`kind=CLAIM` pins the original revision ID, subject and predicate IDs, exact typed
object, Claim version, state, actor, time and trace. CONCEPT, STRING, NUMBER, DATE
and BOOLEAN are supported. Decimal and boolean values remain text. Numbers never
pass through floating point; the consumer validates canonical scale/precision.
Strings preserve up to 1000 Unicode codepoints and render escaped. Dates are
literal Gregorian dates, without timezone conversion. The generic list title
is 개념 관계; current Concept names are not presented as historical names.

Detail retains DRAFT verification. Citations and Qualifiers do not create Claim
revisions or these activities. Changing their pins is separate work. Retiring
the Claim or a referenced Concept never removes already recorded activities.

**현재 활동 원본 확인** reads the exact same-Space Claim endpoint and validates
Claim/subject identity, object, lifecycle, visibility and strong cl-version ETag.
The current summary appears separately. A retired/unavailable reference returns
404 while history remains visible. Failed replies clear the old current summary;
closing/filtering/changing Space discards delayed replies. Revoked authority clears
private state. History/current reads do not modify editor drafts.

## Event recovery and rollout

Both `knowledge.claim.v1` and `knowledge.claim.v2` are consumed under the existing
Activity group, now 22 literal topics / 21 activity kinds. Frozen V1/V2 schemas,
registry artifacts, Knowledge producer, connector, replication slot and offsets
are unchanged. Transport validates the topic-specific logical event version and
registry artifact, producer, event ID, aggregate key and trace. Durable Inbox
deduplicates original event IDs/bytes; out-of-order revisions remain distinct
historical rows. Earlier stored Activity JSON with no claim field still reads.

The new `/internal/knowledge/activity-events` endpoint exports original retained
Claim envelopes from the Knowledge-owned Outbox only, bounded to five rows per
page at a fixed inclusive watermark. It requires HTTPS and a separate service
credential; browser cookies and unrelated API keys cannot use it. No public
Gateway route is added. The existing Learning/Planning/Content scans plus this
Knowledge scan must all complete before Activity becomes ready. Failure preserves
the unseeded state for retry. Outbox retention remains required.

Activity V9 requests warmup by changing readiness flags only. It does not delete
rows or reset checkpoints. There is no canonical schema change. Explicit operator
replay now includes four owner HTTPS exports, with a bounded Activity-owned pool
and no Kafka credential or API Service selector. Original Inbox and ClickHouse
event IDs make repeated replay idempotent.

1. Run `Provision-LocalActivity.py` to preserve existing credentials and add the
   scoped Knowledge activity key and two literal Kafka Read/Describe ACLs.
2. Build/import Knowledge, Activity and Gateway images on both local nodes.
3. Run `Install-LocalClaimActivity.py --helm PATH`. It verifies exact imports and
   deploys the Knowledge export, then Activity warmup, then Gateway UI.
4. Run focused Knowledge and Activity/storage backend checks and
   `Run-GraphWebValidation.py --claim-activity-only --no-images`.

The same owner/API configuration applies locally and in production. Knowledge
ingress admits Activity and the isolated replay Job. Existing bounded Activity
metrics, recovery/failure counters, trace logs and the new Knowledge export count
cover this path. Central telemetry deployment and final frontend design remain
later work. Keep the Claim-aware Activity reader after these rows are projected;
older strict JSON readers cannot read the new field/kind.

Contracts: `activity-timeline.yaml`, `activity-internal.yaml`. Validation is recorded
in `claim-activity-validation.txt` and `.local/claim-activity-proof.json`.

## Measured recovery budget

The first rollout scanned 30,899 Learning, 9,635 Planning, 88,693 Content and
13,976 Knowledge events in about 625 seconds. The previous 600-second Kafka poll
budget and Kubernetes progress deadline expired before the completed scan.
Activity now defaults to a configurable 1,800,000ms poll budget (allowed
600,000..3,600,000) and a 1,800-second deployment progress deadline in both runtime
profiles. This changes operational limits only. Live polling/heartbeats remain
unchanged. The installer waits up to 1,800 seconds.

After all canonical events are durably staged, recovery seeks and commits exactly
the Kafka end offsets captured **before** the scans. It does not commit later
positions. Concurrent events therefore replay safely through the existing Inbox,
and even an empty legacy topic gets a retained checkpoint. Failed backfill never
seeks or commits. Commit failure leaves staged facts safe for retry. Ordinary
per-record acknowledgement still follows durable staging.
