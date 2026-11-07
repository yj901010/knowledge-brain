# Private Claim revision comparison

Open `/graph` → 개인 개념 보관함 → select a Concept → select a relationship →
관계 수정 이력 → **관계 버전 비교**. Choose an earlier base revision and a later
target revision. The defaults are the two newest loaded versions. Previous pages
extend the choices without replacing the selected pair. Refresh starts again
with the newest loaded pair; one revision shows an explicit empty state.

The comparison shows predicate Concept ID, object kind, exact object ID/value,
lifecycle state and verification state. Each side includes its original revision
ID, actor ID and timestamp. A filter shows changed fields only. Same or reversed
versions cannot form a comparison. Distinct revisions with identical content are
allowed and explicitly report zero changed fields.

Numbers are compared as exact decimal strings, dates as recorded dates, and
booleans as literal true/false text. Changing NUMBER to STRING while preserving
the same text still changes the object kind. Concept IDs are shown without
hydrating current names as historical facts. String values render escaped,
preserve line breaks and wrap on narrow screens. Retired Claim revisions can be
compared from the existing selected history view.
The [retired relationship catalogue](retired-claims.md) also reopens explicitly
retired Claims and supports the same comparison from their retained revisions.

This is a read-only use of the existing Knowledge-owned revision endpoint. It
does not change a Claim, restore a version, repin a Citation/Qualifier, or change
another editor's draft. Citation and Qualifier audits remain separate. The
comparison appears only after the parent reader validates identity, typed value,
version order and metadata. Duplicate revision IDs within or across pages are
rejected. Failed initial/next-page reads clear old snapshots until an explicit
retry. Late replies after selection changes or closure are discarded; fresh
authorization denial clears private UI state. Nothing is saved in browser storage.

AGENTS.md and PROJECT.md were reviewed before implementation. No architecture
decision conflicts: ownership, private defaults, Space authorization, service
boundaries and canonical immutable revisions remain as specified. There is no
API/event/schema change, new dependency or external dataset. Existing HTTP and
Knowledge read telemetry covers the feature.

## Validation and deployment

Build the pinned frontend and Gateway using `Build-LocalSpaceImage.py --service
gateway`; wait for its printed builder Pod, then run `Import-LocalSpaceImage.py
--service gateway` and verify each printed import Job on both local nodes. Set
the local Gateway image tag to the generated `.local/gateway-image.json` value
and upgrade its existing Helm release with the base/local Gateway values.

Run `Run-GraphWebValidation.py --claim-comparison-regressions --no-images` after
the rollout. This includes real HTTPS comparison fixtures and the existing Claim,
typed-object, Citation, Qualifier and Claim activity browser suites. Source APIs
are unchanged; new tests compare immutable history and evidence pins before and
after reads, exercise rejected history, deferred replies, and actual revocation.

Results are recorded in `claim-comparison-validation.txt` and
`.local/claim-comparison-proof.json` after successful local Kubernetes validation.
