# Private Concept external mappings

Current extension (2026-10-20): [typed Claim objects](typed-claims.md) adds scalar
objects and V2 Claim/Citation/Qualifier events, preserving prior history. The
following original-stage evidence remains historical; use the typed-Claim
rollout/recovery guidance for the current deployment.


AGENTS.md and PROJECT.md were reviewed before implementation. External Mapping is
an existing Knowledge Core entity. Knowledge owns canonical mappings, revisions,
Concept/Source snapshots and Outbox events. Existing service boundaries, common
guest/registered sessions, Space authority and private defaults are preserved.
No dependency or external terminology dataset was added.

## Use

Open 개인 개념 보관함 → select a Concept → 외부 체계 연결.
Create the evidence in 개인 출처 보관함 first. Choose 새 외부 연결 작성, enter
the external system, code, optional edition and optional label, and select a
Source. Use 연결할 최신 개념·출처 확인, inspect the actual Concept description and
Source citation, URL and license note, then explicitly accept those versions
before saving. Existing mappings support editing, retirement and paged history.

All mappings are private CANDIDATE records. Mapping state is independent of
ACTIVE/RETIRED lifecycle and Claim verification. An entered code or license note
does not establish correctness, official status or data redistribution rights.
No external URL is fetched, and no data is published or imported.

Identifiers are case-sensitive opaque text: leading zeros remain intact.
System/code/edition/label are trimmed, with limits of 100/200/100/200 Unicode
codepoints; system and code are required. Controls and invalid surrogates are
rejected. Empty edition means unknown, never latest. Only one active tuple of
Concept + system + code + edition is permitted (409 on duplicate); a different
edition or identifier may coexist. A retired tuple can be re-added under a new ID.

## API and immutable provenance

Base: `/api/spaces/{spaceId}/concepts/{conceptId}/external-mappings`.
GET/POST collection, GET/PUT/DELETE item, GET item `/revisions`.
The exact write body has system, code, systemVersion, label, sourceId,
sourceVersion and conceptVersion; extra fields (including mappingState) fail.
Only active private Concepts and Sources in the same Space are accepted.
Every request uses fresh Space authorization; mutations require trusted Origin.
Body limit is 16 KiB. Responses are no-store. There is no public write route.

Mappings have UUIDv7 IDs and independent strong `"xmN"` ETags. PUT/DELETE require
If-Match: missing 428, malformed 400, stale 412. Writes also check the explicitly
inspected Concept and Source versions while holding their locks. Each mapping
pins immutable Concept and Source revision IDs and full provenance. Later edits
to those records do not change the mapping representation or its ETag. Explicit
PUT pins current inspected versions and preserves earlier snapshots in history.

DELETE retires a mapping without removing revisions. Source retirement retains
existing pins; retirement of a mapping still works without an active Source.
New saves need an active Source. Concept retirement blocks active reads/writes,
while authorized known mapping history remains readable.

Collection paging uses exclusive UUID cursors (default20/max100). History uses
exclusive version cursors (default5/max10). The UI loads20 mappings or Sources
and5 revisions at a time. It retains input on failed/stale writes, displays latest
stored content and both current references before explicitly restarting an edit,
and cancels outstanding requests on parent close/switch. Parent drafts remain
independent; private data is neither stored in browser storage nor rendered as HTML.

## Storage and events

Knowledge migration V10 creates `external_mapping` and
`external_mapping_revision`, with same-owner composite foreign keys and an
active-identifier unique index. Writes lock Concept → proposed Source → mapping
in a local transaction and atomically store state, revision and Outbox payload.
Old reference pins are immutable and do not require locking retired Sources.

`knowledge.external-mapping.v1` carries ExternalMappingCreated,
ExternalMappingUpdated and ExternalMappingRetired, using registry artifact
`knowledge/external-mapping-events` v1 with FULL/FULL_TRANSITIVE compatibility.
The 22-field payload includes both complete pinned snapshots; the envelope
retains the standard11 event/actor/Space/trace fields. Events use existing
Knowledge Debezium routing, literal Connect write/owner-validator read ACLs,
and at-least-once delivery. Existing connector configuration, slots, checkpoints,
topics and projections remain intact. No projection consumer is added here.

Metrics: brain.knowledge.external-mapping.reads/changes. Existing trace/error
handling applies; sensitive identifiers are not used as metric labels.
The future central telemetry and GitOps platform remains separate planned work.

Contracts: `contracts/openapi/personal-external-mappings.yaml` and
`contracts/events/src/main/protobuf/knowledge_external_mapping_v1.proto`.
Build/import Knowledge and Gateway, then run
`scripts/Install-LocalExternalMappings.py --helm <existing Helm executable>`.
The installer requires successful imports on every local node, ready topic/ACLs
and registry validation before application rollout.

Validation is recorded in `docs/external-mappings-validation.txt` and
`.local/external-mappings-proof.json`. Later scope includes reviewed public
mapping promotion, literal Claim objects and independent Claim verification.

## Retired child archives

Explicit read-only discovery of retired Aliases and External Mappings is now
available from active Concept panels and the retired Concept archive. See
[retired-concept-details.md](retired-concept-details.md) for query semantics,
immutable pins, UI behavior and validation. Existing active-list defaults remain.
