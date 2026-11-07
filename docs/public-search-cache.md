# Public search candidate cache

The platform Valkey cache is separate from Argo CD's internal Redis. Brain Query
uses it only for the candidate page of the existing public search endpoint. There
is no domain service, transactional table, new event or private cache. The current
validation state is recorded in `public-search-cache-validation.txt`.

## Data and consistency

Each value contains a schema, query binding hash, absolute expiry, projected total
and at most50 public `(UUIDv7, kind, canonicalVersion)` candidates. Titles, bodies,
search text, cookies, principals and permission decisions are excluded. Keys use
`brain:public-search:v1:<SHA256>` over the exact Space/query/kind/offset/limit tuple.
A digest is a lookup binding, not anonymization or an authorization credential.

The server TTL and independently validated payload lifetime are5 seconds. Payloads
are capped at16KiB; `GETRANGE` bounds the network result to16KiB+1 before decoding.
Strict duplicate/trailing-token/field/integer/UUID/type/page validation rejects
corrupt, oversized, wrong-scope or expired entries and loads OpenSearch instead.
New matches and query-dependent ordering may lag the underlying search projection
by up to5 additional seconds. Existing projection lag is separate.

Every request still performs fresh Space/Identity authorization, public projection
readiness checks before and after, and the Knowledge-owned canonical check for
current state, kind, exact version and reference context. Stale/missing candidates
are omitted. HTTP responses remain `no-store`. Cache hits never provide permission,
canonical content or authority to change data. Rebuild pause and authority/canonical
failures retain their existing fail-closed behavior. Private search/graph is unchanged.

## Failure bounds and observability

The cache has two nonblocking pooled connections and at most two workers with no
task queue. The calling request waits at most125ms for a cache read; DNS/TLS work
cannot create unbounded workers if interruption is slow. Connect/socket timeouts
are100ms. Read errors/timeouts start a1-second cooldown and directly load the
existing OpenSearch source; writes are dropped while busy or in cooldown. Cache
writes are asynchronous and never delay the response. No eager connection or cache
health dependency is added to application readiness. Invalid operator credentials
or unreadable CA configuration are configuration errors at startup.

`brain.public.cache.requests{outcome=...}` records fixed hit/miss/invalid/error/
timeout/bypass/busy/write outcomes; `brain.public.cache.read` measures the bounded
read path. Neither uses query, Space, principal, key or credential labels. Existing
Micrometer/OpenTelemetry export carries these metrics. A hit avoids the OpenSearch
candidate query only; fresh authority and canonical reads remain and can dominate
end-to-end latency. No constant speedup or production load claim is implied.

## Kubernetes and credentials

`infra/charts/brain-valkey` deploys an immutable Valkey9.0.7 image with TLS1.2/1.3
only, a private ClusterIP and NetworkPolicy allowing only Brain Query in brain-apps.
The server has no egress, API token, privileged capabilities or root filesystem
writes. It runs as UID10001. Default ACL is off; public-search can only ping,
GETRANGE/SET its key prefix and issue the SDK's client metadata handshake commands.
Health has only PING. Application TLS uses the existing CA with JSSE HTTPS endpoint
identification, including DNS verification. TLS encryption alone is insufficient.

Valkey uses64MiB `maxmemory`, allkeys-lru,32 clients, no RDB/AOF and no PVC. Its
emptyDir is disposable. Local requests/limits are25m/96Mi and500m/256Mi. A cache
restart or replacement loses only candidates, which refill from the read model;
no canonical backup or restore step is needed for cached values. Existing cold
backup inventory still includes the deployment/configuration/Secret, not cache keys.

Provision with `python scripts/Provision-PublicSearchCache.py`, then apply the common
chart with the local values. Provisioning retains existing passwords, signs a
server-only certificate with the existing local CA, and never exports its private
key. Renewal/credential rotation requires an explicit controlled rollout of both
consumers/server; Secret file changes alone do not restart a running pool/server.
Common application values default the optional feature off; enable it after the
TLS/ACL Secret and cache endpoint are provisioned. Local values enable it.

Build the Query reactor through `package` (its compatibility libraries shade during
that phase), execute the tests, scan the exact image archive, import on both local
nodes and roll only Brain Query with the existing service chart plus its observed
values. Pin the imported manifest digest. Preserve unrelated workloads/PVCs.
`Run-PublicSearchCacheProbe.py --javac <Java25 javac path>` verifies the exact running
application transport in confined allowed/denied Jobs; it accepts `--image` for an
already imported immutable candidate. Jobs contain no domain DB or operator keys.

## Dependency review

Valkey9.0.7 retains both BSD-3-Clause notices in the pinned upstream COPYING.
The official Java client `io.valkey:valkey-java:5.5.0` is MIT at tag v5.5.0,
commit ddbb69da56520d40e63f728af76b223c50fa1e75. Runtime dependencies resolve through
the existing Boot4.1.2 BOM: Commons Pool2.13.1 and Gson2.13.2 (Apache-2.0),
SLF4J2.0.20 (MIT), plus JSON20240303 (its exact release declares Public Domain).
Optional resilience libraries are not imported. Commercial use and redistribution
are permitted under these notices; preserve all bundled third-party notices.
SDK/JSON/Gson notices are included in the application image's existing legal directory;
the other libraries retain their packaged notices. Source/license hashes and
Valkey index/amd64 image digests are in `infra/dependencies/valkey/lock.json`.

Primary sources: [Valkey releases](https://valkey.io/download/),
[Valkey9.0.7 COPYING](https://github.com/valkey-io/valkey/blob/9.0.7/COPYING),
[client v5.5.0 license](https://github.com/valkey-io/valkey-java/blob/v5.5.0/LICENSE),
[exact JSON release license](https://github.com/stleary/JSON-java/blob/20240303/LICENSE).

The initial image scans are vulnerability inventories for these exact artifacts,
not a complete infrastructure audit or proof that no exploitable issue exists.
