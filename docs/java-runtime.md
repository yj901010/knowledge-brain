# Java runtime and local image upgrades

The13 services use Java25. The shared Spring Boot parent, managed dependency
versions and digest-pinned runtime image are defined in the root POM;
`infra/dependencies/java-runtime.json` records the runtime and license sources.
Every service keeps its own image, database ownership and container profile.

The current runtime is Temurin25.0.5+8 on UBI10, with Boot4.1.2. Containers run
as10001 with a read-only root and a writable temporary volume. Original runtime
licenses/EULA notices are included in every image. A runtime update must preserve
these files and check native-codec loading in the actual runtime image.

The later public cache package adds the official MIT Valkey Java client only to
Brain Query. Query now uses immutable local-valkey-0f84a5b68ab11bf1,
sha256:16d4c42afbcbb8c40a02c8704997114ff470b995a5c33f7c96a46b784989ba1a.
At that cache milestone, the other twelve retained their earlier runtime images.
Later Learning/Gateway changes are listed below. The same Boot/JDK/graph
compatibility baseline remains. See [cache evidence](public-search-cache-validation.txt).

## Graph compatibility dependencies

Brain Query retains JanusGraph1.1.0 and its existing CQL driver/storage contract.
Two own-GAV library builds replace embedded copies that root dependency
management cannot override:

- `libraries/gremlin-shaded-patched`: original relocation/Kryo layout with actual
  embedded Jackson2.18.11. Standalone Boot Jackson remains independently managed.
- `libraries/java-driver-shaded-guava-patched`: the original driver bundle with
  the upstream deserialization-capacity clamp backported to the original
  `MapMakerInternalMap` family. Other class bytes, Graal substitutions and
  serialization identities remain unchanged.

Read each module README and embedded provenance before updating/removing it.
Build through root `mvn verify` or per-service `-pl ... -am verify` so the
compatibility artifacts are packaged before Brain Query tests. Source bundle
builders include `libraries` together with services/contracts.

## Upgrade sequence

1. Read `AGENTS.md`, `PROJECT.md` and current status. Verify upstream versions,
   licenses, actual bundled copies and driver/storage compatibility.
2. Use `scripts/Run-DeliveryCI.py --images` for isolated owner databases,
   exact-source frontend assets and13 independent images. A supplied
   `--frontend-proof` is accepted only after source/asset hash validation.
3. Retain image archives, raw scan results, test skips and source receipts.
   Scan each exact archive before import; no scanner error or HIGH/CRITICAL
   finding may be waived. Check nested dependencies and actual class origins.
4. Test native codecs, malformed-input boundaries and original serialization
   controls in the actual non-root runtime. Run relevant real Cassandra,
   MinIO, Kafka/CDC and authorization checks.
5. Import the same archives into both local k3d nodes. Compare manifest and
   configuration digests, retain previous images and prepare image-only Helm
   changes. Use `image.digest` with `image.tag` empty; the chart rejects both.
6. Roll backend services serially and Gateway last. Keep Deployment/PVC
   identities, all other configuration and credentials unchanged. Retain a
   private pre-upgrade control and compare it after the rollout, including an
   existing credential and a new Outbox-to-read-model update.
7. Reconnect only the owned loopback Gateway forward after Pod replacement.
   Run relevant trusted-HTTPS browser suites, Registry/CDC and central metrics
   checks. Refresh the isolated GitOps probe with the same patched digest.

The repeatable GitOps drill preserves the revision field owned by its sync
operation, rolls its read-only Git server when fixture bytes change, and checks
new commit refs before sync. Its scope remains `brain-delivery-probe`.

The2026-11-06 local rollout evidence is retained in the managed Codex Security
collection for this repository under `artifacts/runtime-upgrade/`, including
`final-report.json`, `closure-runtime-matrix.json`, `rollout.json`,
`continuity-after.json`, `browser.json` and `registry-live.json`. Historical
`docs/delivery-scan-findings.json` describes the initial older Gateway image.
Remote GitHub/Actions/GHCR publication and GitOps adoption remain separate,
explicitly reviewed operations. See `docs/current-status.md` for current scope.


2026-11-07 frontend maintenance changes only Gateway to immutable manifest1658fe8e9579aa0e8d1bb00463fe4c44c1aa60fab0340e1bd0dd0a63df2dddf9. Its existing Java25/Boot4.1.2/runtime base remain; Next16.4.5 is still a static export with no Next server. Gateway243 checks and exact-image0 HIGH/CRITICAL passed. Brain Query keeps the separately verified Valkey manifest; other services retain the original runtime baseline. See [current validation](public-tables-accessibility-validation.txt).


Current2026-11-07 audit candidate: Gateway is sha256:466049bd04b131a018569ab75c95f8f4ea28d54153a7d25f4101461735c62c71;
Learning retains aba561be5c4749b8ffd22355ee84f6ae086ac00508faf6f244e6ae52cea3d8a8,
Query retains16d4c42afbcbb8c40a02c8704997114ff470b995a5c33f7c96a46b784989ba1a,
and the other ten retain their verified baseline. The original graph PG-only
method could return without graph assertions; it now reports an explicit Graph
environment skip while retaining two PG-class tests. Historical graph-live logs
separately record all three methods executed against real Cassandra. Current
Query106 application classes match the deployed archive; this is a test-only
correction. See [latest scope](unicode-contracts-validation.txt).
