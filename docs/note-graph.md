# Note graph storage, live projection and rebuild

Status (2026-10-08): Cassandra, the Java 25 JanusGraph adapter, live dual-topic Kafka projection and canonical rebuild are deployed locally. The fresh Space-authorized Graph API and Next.js Graph View are deployed and validated; see [note-graph-api.md](note-graph-api.md). Existing Note search runs in the same Query artifact.

## Ownership and runtime

Content owns canonical notes, stable Wiki Link bindings and link revision snapshots.
Brain Query owns only the derived graph. Its JanusGraph 1.1.0 Java library embeds in
the query/projector process and persists the graph in Cassandra 4.0.21; there is no
in-memory substitute or public Gremlin/code execution endpoint. Multiple query
instances share Cassandra. Composite indices support note ID and Space/visibility
lookups; graph mixed-index integration is not needed for this Note graph foundation.
Existing OpenSearch remains the full-text projection.

No cross-service SQL, foreign keys or canonical graph writes are introduced. Graph
state is disposable derived data. Canonical Content note/link snapshots and concurrent
event replay rebuild the graph; seven-day Kafka retention is not the only reconstruction source.

## Private storage

The Helm chart uses one local replica, a 512 MiB heap and 10 GiB PVC with a 2 GiB
memory limit. Local and production retain the same StatefulSet, TLS, authenticated
CQL, service/role boundaries and persistence architecture; replica/resource/replication
configuration can scale. Actual light-load measurement: approximately 910 MiB memory
and 12 millicores; this is not a capacity or performance benchmark.

CQL 9042 requires TLS. Internode 7001 requires TLS and client certificates. Neither
JMX, CQL nor internode ports are externally published. NetworkPolicy permits CQL
only from Brain Query in brain-apps or explicitly labelled brain-data operator/test
Pods. Node-specific stable ClusterIP Services advertise the discovered addresses;
certificates contain those IPs and DNS names, keeping hostname verification enabled
when a Pod receives a different IP after restart. Reprovision SANs before expanding
the node service set. The shared local node leaf has a 60-day maximum lifetime.

brain-graph-admin is a privileged operator Secret in brain-data only. The application
brain-graph-projector role has SELECT/MODIFY on brain_graph, without CREATE/ALTER,
foreign keyspace or authentication-table access. Operator initialization creates the
fixed JanusGraph tables/schema/indexes before the runtime role opens the graph.
The default cassandra account is disabled. The CA private key remains in brain-data
and is never written to disk, logs or an application Secret. Keytool builds Java
truststores from the public CA; credentials stay in scoped mounted Secrets.

## Projection semantics

NoteGraphProjection consumes the existing NoteLinksChanged Protobuf contract and
validates producer/type/version, canonical UUIDv7 identifiers, lifecycle, occurrence
ordinals, offsets and bounded snapshots. Link revision ordering uses linkVersion,
not noteVersion: target auto-resolution may change links without changing the Note.
Only RESOLVED links produce graph edges. Unresolved, ambiguous and retired targets
do not create visible graph nodes. Distinct occurrences retain distinct link IDs.

NoteGraphStore maintains independent noteVersion/linkVersion tombstones and snapshot
fingerprints. Duplicate snapshots are idempotent; older snapshots are ignored;
conflicting same-version projected title/state or link snapshots, or cross-Space ID reuse fails the whole transaction.
A resolved target arriving before its Note event is an invisible placeholder until
its ACTIVE Note revision arrives. A retired link snapshot also hides its source
before a delayed NoteRetired event. Cassandra-backed JanusGraph consistency locks
protect version properties and the unique note ID index; a test uses two separate
clients to verify conflicting concurrent version writes have only one successful
writer. Explicit graph adapter validation/conflict errors use generic messages.

Reads require an already-authorized Space argument, use a Space/visible composite
index, return at most 200 nodes/1000 edges, and only include edges between selected
visible nodes in that Space. Titles are stored only for active nodes; retired notes
retain version tombstones with empty titles. Markdown and unresolved target tokens
are not stored in graph vertices/edges. Node/edge lists are immutable and sorted.
This adapter does not authorize browser credentials: the future transport must call
Space afresh before returning titles, counts or edges and fail closed on outages.

The live consumer uses durable eventId/payload deduplication, a projection queue,
retry/readiness/metrics and Kafka offsets in Brain Query's own store. A separate
read-only Kafka identity reads both existing Content topics; no new producer is introduced. The subsequent authorized public graph route is documented in [note-graph-api.md](note-graph-api.md).

## Run and verify locally

```powershell
python scripts/Install-LocalCassandra.py --helm C:\path\to\helm.exe
python scripts/Run-CassandraValidation.py
python scripts/Run-CassandraValidation.py --network-denied
python scripts/Run-BackendValidation.py --brain-query --graph-storage --search-storage
python scripts/Run-CassandraRestartValidation.py
```

The installer checks the exact local kubeconfig/context, creates Services before
issuing SANs, reuses existing credentials and runs confined account bootstrap. It
never deletes a cluster/PVC or changes the user's global kubeconfig. Config and public
certificate revision annotations roll the StatefulSet when either changes. Validation
Jobs do not mount Kubernetes API credentials. Newly created Pods wait briefly for
network-policy propagation before positive connectivity assertions.

The restart verifier first establishes CQL and graph proof data, replaces only
brain-cassandra-0, checks a changed Pod UID and unchanged PVC UID, and verifies both
CQL and JanusGraph values with fresh clients after replacement. Its proof is stored
in ignored .local/cassandra-restart-proof.json, without credentials. This verifies
Pod restart persistence, not cluster-loss recovery or production HA.

Evidence: [note-graph-validation.txt](note-graph-validation.txt).
Dependency records: infra/dependencies/cassandra.json and janusgraph.json.
Official compatibility: https://docs.janusgraph.org/changelog/
Cassandra maintenance releases: https://cassandra.apache.org/_/download.html

## Live projection and canonical rebuild

Brain Query consumes content.note.v1 and content.note-links.v1 with a separate read-only Kafka mTLS identity and brain-query-note-graph-v1 group. Its own PostgreSQL Inbox retains raw payload fingerprints and independent note/link version queues; graph commits precede durable projected markers and Kafka checkpoints. Cassandra outages fail graph readiness and retry from durable work. Resolved links only are projected, with immutable Space ownership and retired-note tombstones.

Install the imported local Query image with scripts/Install-LocalNoteGraphProjector.py --helm PATH. The installer first runs the artifact with graph disabled, then stops every Query replica for a canonical rebuild, and finally enables the graph profile. scripts/Rebuild-LocalNoteGraph.py stops consumers, resets only the fixed derived brain_graph keyspace, initializes schema using an isolated admin Job, rebuilds through restricted HTTPS canonical note/link APIs with the projector role, replays both captured Kafka tails, commits offsets and restores Query replicas. Failed destructive rebuilds remain paused for repair; original Content data, search state and Cassandra PVC are retained. The subsequent Graph API/UI delivery is documented in [note-graph-api.md](note-graph-api.md).

Runtime verification: scripts/Run-GraphProjectionValidation.py creates real guest notes through Gateway and checks the private derived graph. scripts/Run-GraphProjectionFailureValidation.py uses confined operator coordination to stop/replace Query and Cassandra, asserts actual Query readiness failure/recovery, and validates concurrent writes during a canonical rebuild. Existing Gateway keep-alive connections can continue serving independent search even when Query readiness is down; readiness is measured through Kubernetes, not inferred from search HTTP status. These local tests preserve original replica counts and the Cassandra PVC. Evidence: [note-graph-projection-validation.txt](note-graph-projection-validation.txt).

## Retry-safe Cassandra truststore initialization

A Docker/container sandbox restart can retain an existing Pod emptyDir while
rerunning its init container. Importing the same `brain-ca` keytool alias again
then fails and prevents Cassandra/graph readiness. The init command now removes
only its generated `/trust/truststore.p12` before importing the current mounted CA.
The trust volume is a separate 1 MiB emptyDir; the data PVC is unaffected. Import
failure still prevents startup and no old CA is silently reused.

`scripts/Run-CassandraTruststoreValidation.py --helm PATH` renders the actual chart
command into a confined non-root Job. It reproduces duplicate-alias rejection and
runs the corrected command twice against the same emptyDir, checking the alias
without logging passwords or certificate contents. Evidence for the observed
recovery: cassandra-truststore-validation.txt. An already-failed old Pod may need
replacement after Helm upgrade; preserve and verify the data PVC identity.
