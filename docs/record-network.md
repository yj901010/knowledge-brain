# Personal records in integrated graph and search

Current extension (2026-10-20): [typed Claim objects](typed-claims.md) adds scalar
objects and V2 Claim/Citation/Qualifier events, preserving prior history. The
following original-stage evidence remains historical; use the typed-Claim
rollout/recovery guidance for the current deployment.


Bookmarks, Questions and Decisions now appear as their own typed nodes in
**개인 지식 통합 탐색** on `/graph`. The existing region label remains for compatibility.
Search can select each kind, match title or original body fields, or match the
current title of a linked active same-Space private Concept. Selecting a result
fetches its current authoritative Content API, with fresh authorization and the
source-specific bm/qu/dc ETag. Text remains escaped; Bookmark URLs are not fetched.
Note editing and independent record/link drafts are unchanged.

## Ownership, consistency and privacy

Content retains canonical bodies, immutable revisions, associations and Outbox.
Brain Query consumes nine existing immutable literal Kafka topics: the prior five
network topics plus content.bookmark.v1, content.question.v1, content.decision.v1
and content.record-concepts.v1. No event/schema version is edited or republished.
The network-projector Kafka identity receives Read/Describe on these exact four
topics, no write/wildcard access. Its existing group is preserved.

Brain Query V4 only widens type constraints in its own disposable PostgreSQL
snapshot/work tables. Types and stable source IDs distinguish records; association
aggregate ID remains the transport key while the typed source ID identifies its
projection. Association ID and Space are immutable. Source body and association
versions advance separately. UUIDv7 IDs, standard envelopes, event-ID byte dedup,
same-version fingerprints, tombstone and stale-version handling remain enforced.
Previously persisted five-kind snapshots receive explicit defaults for the added
transport/source-version fields while being read. Their stored bytes and original
checkpoints remain intact; the legacy-format regression test covers this upgrade.

Association-first delivery creates a hidden typed placeholder. Parent archival
hides title/body and every outgoing edge, including when a delayed association
event arrives later. A source edit cannot overwrite links. Concept rename/retirement
queues every dependent record without modifying its canonical body/version/audit.
Retired or foreign Concepts add no text or edges. As with the existing private
Note/Concept/Claim network, public Concept references remain canonical references
but are excluded from this PRIVATE PERSONAL projection; no public/foreign metadata
is copied into a personal graph. Removing/retiring a target preserves source nodes.

The existing durable own-DB fan-out queue drives both JanusGraph/Cassandra and
OpenSearch with projection sequences independent of canonical source versions.
Partial external-store failure leaves work pending for idempotent retry. The
existing fixed index schema and graph labels already support keyword kind values;
no index, Cassandra keyspace, PVC, canonical row, topic, offset, slot or connector
is reset. Readiness fails closed on unavailable projections; there is no stale
fallback. Every graph/search request still makes a fresh Space BRAIN_GRAPH/
BRAIN_SEARCH decision; source detail uses Content's typed read permission.

Graph is bounded to200 nodes/1000 edges. Search20 default/50 maximum, offset window
1000, plain text only. Closed/revoked views ignore late responses and clear private
data; drafts/results stay in memory, never local/session storage. Bounded existing
kind/request/failure metrics and HTTP/Outbox/Kafka traces are reused; centralized
telemetry remains platform work. No dependency, dataset, automatic fetch or AI.

## Recovery and operation

The operator-only fixed-origin HTTPS Content brain-snapshots feed adds six kinds:
BOOKMARK, QUESTION, DECISION and their _CONCEPTS counterparts. Five rows maximum,
UUIDv7 source-ID ordering, fixed upper scan bound, scoped existing Content key,
no browser cookies/duplicate credentials. Source snapshots use current immutable
body revisions, including archival. Association snapshots use their own original
revision actor/time/source-version/state, even after parent archival: no fake link
revision or event is invented. Parent feeds supply current lifecycle independently.

`python scripts/Rebuild-LocalBrainNetwork.py --refresh-records` stages these six
canonical feeds and drains the existing durable queue without pausing consumers,
resetting stores or changing Kafka checkpoints. The live consumer continues to
catch concurrent writes. This command is idempotent and covers pre-existing records
even beyond event retention. Feed keys mount only in the bounded operator Job.
It requires a healthy live consumer checkpoint; an existing Kafka retention gap
still fails closed and requires the full operator rebuild. Refresh does not move
checkpoints or clear that safety condition.
Prolonged validation exposed Cassandra eviction when its upstream file/GC defaults
exceeded the existing64MiB log EmptyDir. The existing chart now rotates active
application files at4MB with8MB archive caps and GC files at4MiB/3 rotations.
The deployed JVM/configuration are checked; the same data PVC is retained. No
keyspace, canonical/read-model state, credentials or Kafka checkpoint is reset.
The existing full-rebuild path now covers eleven feed kinds/nine captured topic
tails, and preflights all feeds before its existing operator-only derived reset.

Backend validation uses --content-kafka --planning-kafka --notification-kafka
--activity-storage --search-storage --graph-storage. Browser --record-network-only
then full --public. Existing files/contracts remain compatible. Build/import/install
Content, Brain Query and Gateway on both k3d nodes; preserve all other service
binaries and connector/CDC state. See record-network-validation.txt and local
record-network-proof.json for actual results.

Record activity/timeline is now implemented as the separate Activity-owned increment documented in record-activity.md.
This change adds no activity events or public publication and keeps the agreed
frontend redesign deferred while completing functional navigation first.
