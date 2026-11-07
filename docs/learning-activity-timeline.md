# Private learning activity timeline

Current operational scope (2026-10-18): [Note Concept activity](note-concept-activity.md) extends the timeline to seventeen streams across Learning, Planning and Content. Use its current rollout/recovery instructions; the earlier increment and validation evidence below remain historical.


Activity projects the seven existing Learning event streams into a private, date-filtered timeline. Roadmaps, progress, StudySessions, Note/Concept/Media evidence and evidence-to-session associations are covered. Learning retains all canonical ownership; progress, source versions, durations, and sibling editor drafts are never changed by viewing activity. The feature follows AGENTS.md/PROJECT.md Activity + ClickHouse decisions and adds no AI, public publishing or new domain event version.

## Read flow and semantics

Graph page → **학습 활동 열기** → date/timezone/type filters → explicit refresh or older pages. Details preserve the version/state/title/memo at the time of each change. **현재 활동 원본 확인** performs a separate authorized canonical Learning GET. A retired/missing current source leaves historical activity visible; permission loss clears private surfaces. Text is escaped, requests are cancelled on close/filter/scope changes, and late replies cannot repopulate closed panels. Mobile width is bounded.

`GET /api/spaces/{spaceId}/activity/timeline` uses the common guest/registered application cookie and fresh Space `TIMELINE_READ` authorization on every page. Only visible PERSONAL/PRIVATE Spaces are supported. No-store and empty GET body; no write/rebuild/export browser route. See [external API](../contracts/openapi/activity-timeline.yaml) and [internal APIs](../contracts/openapi/activity-internal.yaml).

Ordering is descending save/change `occurredAt`, then lexical event UUID. StudySession start/end/duration are separate retained metadata, so a backdated session appears at its save time. Inclusive local dates use IANA timezone day boundaries including DST. Dates require an explicit timezone; two bounds allow at most 366 days difference. The default is all retained history, UTC, ALL, 20 rows, maximum 100. A cursor binds Space/date/zone/type and cannot be reused for another filter or Space. Refresh restarts at the newest entry; older pages do not auto-update.

## Reliability and recovery

Learning local transaction → retained Outbox → Debezium → seven Protobuf v1 Kafka topics → Activity-owned PostgreSQL Inbox → ClickHouse timeline. Inbox stage commits before Kafka acknowledgement. Event ID plus exact wire SHA-256 detects conflicting duplicates; topic/aggregate/version is unique. Large canonical bodies/graphs are not copied into the normalized display record. A separate projector inserts bounded batches of at most 100 events, then marks the batch applied in one local transaction. ClickHouse `ReplacingMergeTree` plus `FINAL` collapses replayed identical events, including a crash between insert and mark. Reader has SELECT only; projector SELECT/INSERT only, neither DDL nor other database privileges. HTTPS hostname/CA verification, bounded bodies/timeouts and no redirects are used throughout.

Empty/unseeded Inbox or a Kafka retention gap captures assigned end offsets **before** a fixed-high-watermark, five-row canonical Learning export scan, stages every retained event, then tails from captured offsets. Learning's narrowly authenticated read-only endpoint owns the SQL; Activity never connects to Learning's database. Initial warmup waits for the durable queue to drain. Later processing is eventual; no exactly-once claim is made. Historical events stay private after source retirement. Export covers all service retained events and is an operator/service capability, never a user-facing data export.

For ClickHouse loss/rebuild, restore a reachable empty schema with the storage bootstrap, then run:

```powershell
python scripts/Rebuild-LocalActivity.py
```

The operator Job uses the deployed Activity image and dedicated capabilities, sets durable rebuilding/readiness state, marks retained Inbox entries pending, backfills canonical Learning events, and replays until empty. It does **not** drop a table, PVC, canonical fact or Kafka offset. Readers return 503 while rebuilding; a failure after rebuilding starts leaves readiness false for repair and rerun. The runner refuses another active replay. Its separate Pod label cannot match the API Service, and it carries only database/export/projector capabilities plus public trust. A persistent ClickHouse restart needs no rebuild. Retain Learning Outbox/Activity Inbox (or backup) for historical recoverability; purging both would lose recoverable history and is outside this step.

## Local deployment

1. Existing PostgreSQL per-service DBs, guest API CA/trust, Kafka/Strimzi and seven Learning topics/Registry contracts must be healthy.
2. `python scripts/Provision-LocalActivity.py` creates/reuses dedicated local secrets and exact seven-topic read-only Kafka ACLs. No credentials are printed. Renewed subPath TLS files require workload rollout.
3. `helm upgrade --install brain-clickhouse infra/charts/brain-clickhouse -n brain-data -f infra/environments/local/clickhouse-values.yaml --kubeconfig .local/kubeconfig.yaml --kube-apiserver https://127.0.0.1:6550`.
4. `python scripts/Run-ActivityStorage.py` creates the fixed derived table and scoped SQL users, verifies TLS/anonymous denial and privileges. Inspect its named Job before proceeding.
5. Build/import/install Space and Learning first, then Activity, then Gateway using `Build-LocalSpaceImage.py --service …`, `Import-LocalSpaceImage.py --service …`, and `Install-LocalRoadmaps.py --space-only|--learning-only|--activity-only|--gateway --helm <helm.exe>`. Each owner remains separately deployed. TLS readiness includes Activity DB, ClickHouse and Kafka warmup. Local one replica/Recreate and memory settings are configuration only.

Pinned official ClickHouse LTS image/license/digest: [dependency metadata](../infra/dependencies/clickhouse.json), [Apache-2.0 license](../infra/dependencies/clickhouse/LICENSE). SQL HTTPS is private on 8443; no host SQL port is exposed. Three GiB persistent volume, 256 MiB request/1536 MiB limit, bounded background pools/caches suit local development, without claiming production HA or a load benchmark. SQL/trace/metric system logs are disabled locally to bound disk/memory.

Bounded activity category/outcome counters, Hikari metrics, readiness and event ID/trace correlation support the existing observability foundation. Central Prometheus/Loki/Tempo/Grafana deployment and load/lag dashboards remain platform work. Note/Journal event activity, planning, GitHub and other domain activity are separate future consumers; this step is the Learning timeline only.

Validation evidence: [learning-activity-timeline-validation.txt](learning-activity-timeline-validation.txt).


Planning Goal/Task/Schedule events now join the [unified private activity timeline](planning-activity-timeline.md); this extends the earlier Learning-only scope without changing canonical ownership or existing event versions. The panel is now **활동 열기**.
