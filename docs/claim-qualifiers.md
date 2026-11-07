# Private Claim qualifiers

Current extension (2026-10-20): [typed Claim objects](typed-claims.md) adds scalar
objects and V2 Claim/Citation/Qualifier events, preserving prior history. The
following original-stage evidence remains historical; use the typed-Claim
rollout/recovery guidance for the current deployment.


AGENTS.md and PROJECT.md were read before implementation. Qualifier is an existing
Knowledge Core entity: the Knowledge service owns its tables, revisions and events.
This feature uses existing CONCEPT_READ/CONCEPT_WRITE authorization and the common
guest/registered application session, with PRIVATE defaults. No architecture,
dependency, external dataset, verification policy or service boundary changed.

## Using conditions

Open 개인 개념 보관함 → a Concept → 개념 관계 → a Claim → 관계 조건 (Qualifier).
Use 새 조건 작성, choose a predicate Concept (for example 적용 대상 or 기준 날짜),
choose the value kind, then 조건에 적용할 최신 버전 확인. Inspect the displayed
Claim triple and explicitly choose 확인한 버전으로 조건 작성 before saving.
Condition predicates and Concept values use actual paginated active private
Concepts in the same Space; duplicate titles remain distinct by ID.

Five value kinds are supported:

- STRING: nonempty trimmed text, at most 1000 Unicode codepoints.
- NUMBER: exact plain decimal string, at most 38 significant/integer digits and
  18 fractional digits; no exponent. Canonical normalization removes trailing
  fractional zeros and normalizes negative zero without floating-point rounding.
- DATE: valid Gregorian YYYY-MM-DD, years 0001 through 9999.
- BOOLEAN: canonical string `true` or `false`.
- CONCEPT: canonical UUIDv7 of an active private Concept in this Space.

All API values are `{type, value}` with `value` as text. This preserves decimal
precision across JSON, Java, PostgreSQL, the UI and Protobuf. A unit or comparison
meaning is conveyed by the predicate Concept; this initial feature does not
evaluate formulas, coerce units, or infer verification.

Each condition has a UUIDv7 and independent strong `"qfN"` ETag. PUT and DELETE
require If-Match (missing 428, malformed 400, stale 412). Writes include the
explicitly inspected current Claim version, which also returns 412 when stale.
The UI keeps inputs on failure and displays the latest stored condition and
Claim before explicitly restarting an edit. Create retries cannot create an
identical active predicate/type/canonical-value tuple: duplicates return 409.
Different values of the same predicate are allowed.

## Retained meaning and lifecycle

A condition pins an immutable Claim revision with its exact triple. Updating
the Claim does not update existing conditions or their ETags. A later explicit
condition save pins the newly inspected Claim; the old snapshot remains in
condition history. Ordinary Claim, Concept and Citation versions are unchanged.
The UI displays pinned versions and the full referenced triple, with IDs when
Concept titles are not loaded.

DELETE is retirement: the condition disappears from active lists but keeps its
revisions and events. A predicate or Concept value retiring does not erase an
existing condition snapshot; new saves require active proposed references.
Retiring a Claim or one of its triple Concepts blocks active condition reads
and writes. Known condition history remains authorized and readable. Lists
use UUID keyset paging (default 20/max 100), histories exclusive version cursors
(default 5/max 10). Neither is a total count.

The qualifier editor is independent of parent Claim/Concept/Note drafts.
Closing or switching the parent cancels outstanding requests; failures clear
refreshed list rows and keep edit inputs. No browser storage or HTML rendering
is used for private values.

## Canonical consistency and delivery

V9 creates `claim_qualifier` and `qualifier_revision`. Writes lock the union of
current Claim and proposed qualifier Concept references in UUID order before
locking the Claim, then recheck its snapshot version. Claim locking serializes
duplicate checks and avoids a wide UTF-8 unique index. Optimistic qualifier
versions protect concurrent editors. Data, immutable revision and Outbox event
commit or roll back in the same Knowledge PostgreSQL transaction.

`knowledge.qualifier.v1` carries full private snapshots under
`knowledge/qualifier-events` version 1. The eleven-field envelope, stable event
ID, aggregate key, trace and Registry headers use the existing
Outbox → Debezium → Kafka path. Consumers must deduplicate event IDs and order
snapshots by qualifier ID/version. Existing immutable Claim/Citation contracts,
CDC connector, replication slot and checkpoints remain unchanged.

This stream is not yet consumed by graph/search/Activity projections. Canonical
conditions are visible in Claim detail; adding a condition does not add a graph
edge, publish a Claim or elevate its verification. General typed Claim objects,
public Claim contribution and verification remain separate work.

The controller has bounded `brain.knowledge.qualifier.reads` and `.changes`
metrics, propagates validated trace IDs, reuses fresh Space authorization and
same-origin write guards, limits bodies to 16384 bytes, rejects duplicate/trailing
JSON, and returns no-store responses. The central telemetry stack is still
separate platform work.

## Contracts and local verification

- [OpenAPI](../contracts/openapi/personal-claim-qualifiers.yaml)
- [Protobuf](../contracts/events/src/main/protobuf/knowledge_qualifier_v1.proto)
- [Validation evidence](claim-qualifiers-validation.txt)

Run `Run-BackendValidation.py --knowledge --knowledge-kafka` for focused owner
PostgreSQL/API/CDC tests. `Run-GraphWebValidation.py --qualifiers-only --no-images`
tests the actual HTTPS deployment; the full `--public` run includes these cases.
Build and import Knowledge and Gateway images onto both nodes, then use
`Install-LocalClaimQualifiers.py --helm PATH`. The installer verifies exact image
imports, topic/ACL readiness and immutable Registry publication before rollout.
It does not reset canonical data or existing consumers.

Rediscovery after closing/reloading, including retired parents, is now available
through the independent read-only [retired Qualifier archive](retired-qualifiers.md).
