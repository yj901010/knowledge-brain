# Private Note learning evidence

This phase lets a guest or registered owner explicitly connect an existing Note
version to an active private Roadmap node in the same Personal Space. Open a
Roadmap and select **학습 근거 열기**, choose the node and Note, and save an optional
memo. The picker pages through Notes; the evidence catalog pages through saved
links. Memo editing, explicit retirement and immutable history are available.
Existing Note/Roadmap drafts remain independent.

Learning owns the evidence ID, relationship, memo, lifecycle, revisions and
events in `brain_learning`. Content remains the only Note owner. Learning uses
the HTTPS Content reference API with the common session cookie; Content checks
current Space read permission independently and returns only the ACTIVE Note's
ID, version, title, Space, visibility and authorized principal. Learning checks
the principal and same PRIVATE Space and expected version. No cross-service SQL,
join, foreign key, direct write, distributed transaction or Markdown copy exists.
Gateway does not expose the narrow reference endpoint. Content's ingress permits
Learning in addition to the existing authorized callers. No new service-wide
snapshot credential or database privilege is needed.

The pin is `(noteId, noteVersion)` plus the Note title and node title at linking.
The client supplies the expected Note version selected from the owner catalog;
a changed version returns 409 rather than silently advancing it. Source edits or
retirement after this check do not rewrite saved evidence. A source may change
between the owner read and the local commit: this is an explicit historical pin,
not a distributed liveness guarantee. **현재 노트 확인** performs a fresh authorized
read and distinguishes changed or unavailable sources. The pin does not certify
understanding, public knowledge accuracy or medical verification. Linking or
editing evidence never changes manual progress, sessions, Roadmap revision,
Note content, public visibility or ranking.

The root's local row lock serializes graph/node retirement and evidence writes.
New links require an active root and node. Existing links to archived nodes or
Notes remain readable; their memo may be corrected while the root is active.
Root retirement stops all mutations while known records and history remain
readable after fresh Space authorization. Evidence retirement is soft; it removes
the link from the current catalog, preserves pins/memo/history and allows an
explicit later new link if the source and node are active. Other users cannot
infer foreign Notes by linking them in this Space.

Create requires a single 32-character lowercase hexadecimal `Idempotency-Key`.
The opaque request token is not a domain ID. Learning generates UUIDv7 evidence,
revision and event IDs. Same original payload/key replays the current evidence
with 200, even after edit/retirement or source/root retirement, without another
owner read, revision or event. A different payload with that key returns 409.
Another key for an already ACTIVE node/Note/version pin also returns 409. A source
version can be linked to different nodes. Memo changes do not change source pins.
Client retry tokens stay only in component memory; failures retain the draft and
token. No private state is stored in localStorage/sessionStorage.

Evidence uses an independent strong `"evN"` ETag. PUT accepts only `{memo}`;
DELETE has no body. Missing/stale/wrong ETags return 428/412/400. No-op memo saves
check the expected version and emit nothing. Memos are raw text up to 8192 UTF-8
bytes with controls rejected; JSON is strict, bounded to 65536 bytes and rejects
duplicate, unknown and trailing fields. Catalog defaults to 20, max 100 with UUID
keyset; history defaults to 5, max 10 with descending version cursor. Every
operation checks current Space permission, uses no-store, and browser mutations
require same origin. UI requests are independently canceled/scoped, clear private
data on authority loss, ignore late responses and preserve drafts on conflicts.
An explicit latest inspection and restart is required after a 412; retirement
confirmation resets on conflict.

Migration `V3__learning_note_evidence.sql` adds two Learning-owned tables. Each
real change atomically commits its row, full immutable JSON revision and full
Protobuf Outbox event (`LearningEvidenceLinked`, `LearningEvidenceUpdated`,
`LearningEvidenceRetired`). The existing Debezium publication remains Outbox-only.
`learning.evidence.v1` uses the evidence ID as Kafka key and stable event IDs with
actor/Space/trace and schema headers. Registry artifact `learning/evidence-events`
version 1 uses FULL validity and FULL_TRANSITIVE compatibility. Kafka Connect has
exact-topic write privileges and the Learning validator only read privileges.
CDC cannot SELECT any of the ten canonical Learning tables or connect to other
owners' databases. `brain.learning.evidence` counters use bounded operation labels;
HTTP metrics and existing readiness/HTTP-to-Outbox-to-Kafka trace propagation
remain. Central observability stack/exporter wiring is still platform follow-up.

Local rollout order matters: apply the `brain-connect` Helm values and wait for
`learning.evidence.v1` and both KafkaUsers to reconcile Ready **before any new
evidence write or integration test**. Publish contracts with
`Run-LearningTrackingContractValidation.py`, build/import Content, Learning and
Gateway on both local nodes, then use `Install-LocalRoadmaps.py` with
`--content-only`, `--learning-only` and `--gateway` (each requires `--helm`). Run
`Provision-LearningCdc.py` and `Run-LearningConnectorConfiguration.py` and verify
their Jobs succeeded. Connector restart is asynchronous; the readiness verifier
waits the full bounded window instead of rejecting a briefly visible old FAILED
task. It preserves WAL offsets; no reset, truncation or database recreation occurs.

Verify with `Run-BackendValidation.py --learning --learning-kafka`, the complete
backend reactor, `Run-GraphWebValidation.py --learning-evidence-only` and the full
browser suite `--public --no-images`. The latter avoids large screenshot stdout
rotating away the final test result. Feature screenshots are available from the
focused suite. The full public workflow verifies unchanged evidence IDs/pins
across real Keycloak registration and Space claim, followed by registered edits.

Concept/Media evidence kinds, optional StudySession association, public/group
Roadmap learning, ratings/ranking and Activity timeline projection are later
phases. This change implements Note evidence only; it does not mark the entire
Learning roadmap complete. No new dependency, dataset or license was introduced.

Concept evidence is now available through the typed Concept collection; see [concept-learning-evidence.md](concept-learning-evidence.md). The original Note JSON and Protobuf contract remain unchanged. Media and optional session association are still follow-up.

Media evidence is now available in its separate typed collection; see [media-learning-evidence.md](media-learning-evidence.md). Existing Note/Concept endpoints and immutable v1 contracts are retained. Request keys are shared across all three evidence variants.

Optional StudySession association is now available through a separate typed relationship; see [evidence-study-sessions.md](evidence-study-sessions.md). Existing evidence/session JSON and immutable event v1 contracts remain unchanged.
