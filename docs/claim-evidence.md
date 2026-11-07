# Evidence in inverse Concept relationship browsing

AGENTS.md and PROJECT.md were read before implementation. This connects the
existing Knowledge-owned private Claim/Citation read APIs to the inverse Concept
relationship panel. It adds no canonical mutation, migration, dependency, event,
cross-service SQL or architectural decision.

Open **개인 개념 보관함**, select a Concept, select an OBJECT or PREDICATE usage
in **이 개념을 사용하는 관계**, then choose **사용 관계 근거 확인**. The original
subject/Claim identity determines the route even when the selected Concept is
the predicate or object. Opening evidence is explicit; the panel does not fetch
the Source picker or open a relationship editor.

The evidence catalog has independent 20-row exclusive Citation-ID pages.
Duplicate Source titles are distinguished by Citation IDs. Rows label the pinned
Claim and Source versions, rather than presenting them as current values.
Displayed counts describe loaded rows only. Select **사용 관계 근거 열기** to
inspect the complete pinned Source title, URL, citation text, license memo and
Claim/Source revision IDs. Source text and whitespace are preserved and escaped.
HTTP(S) URL opening is explicit, with noopener and noreferrer; reference content
is not fetched, imported or parsed.

**사용 관계 근거 수정 이력** reads immutable Citation revisions, five at a time,
with an exclusive before cursor. It is independent of the parent Claim history.
A pin can describe another Claim version and another predicate/object triple;
it is not presented as evidence for whichever Claim revision is selected above.
A Source connection does not promote the Claim's DRAFT verification state.

## Privacy, lifecycle and asynchronous reads

Reads use the existing common guest/registered principal, verified HTTPS, fresh
CONCEPT_READ authority, private PERSONAL Space and no-store responses. Collection,
item and revision contracts remain
[claim-citations.yaml](../contracts/openapi/claim-citations.yaml). Independent
strong citeN ETags are verified on item reads. The browser validates scoped IDs,
positive safe versions, snapshot shape, dates, row ordering and cursor bounds.

Catalog and detail/history requests have independent generations, selected-ID
checks, AbortController and eight-second bounds. Changing Citation selection,
Claim, role, Concept or Space, or closing the evidence/parent panel discards late
responses. 503 or malformed responses clear the affected stale rows/pins and
offer explicit retry. 401/403 clear the private parent surfaces.

The child key contains only Space, original subject and Claim IDs. Claim/Concept
editor mode or version changes retain the selected Citation and history. Existing
Concept, Alias, outgoing Claim, Note and Source drafts remain independent. The
panel offers reads only, creates no revision/Outbox event and writes no browser
storage.

Source retirement leaves active Citation discovery and its pins intact. Citation
unlinking removes that row from discovery. Claim or referenced Concept retirement
makes active Citation discovery unavailable. These cases retain an already known
authorized Citation route and its immutable history. A collection/item 404 clears
that active view and explains the remaining known history; it does not treat
retirement as revocation or discover arbitrary retired Citation IDs. Explicit
parent Concept retirement refreshes the catalog without unmounting known history.

## Delivery

Build/import the Gateway with the existing local helpers, wait for matching
imports on every k3d node, then use
`Install-LocalClaimUsages.py --gateway-only --helm PATH`. The default installer
continues to deploy Knowledge and Gateway for changes affecting both. This UI-only
delivery does not redeploy the unchanged Knowledge service.

`Run-GraphWebValidation.py --claim-evidence-only` runs the actual API/browser
scenario. The full `--public` suite includes it and existing usage, Citation and
other private/public workflows. Checks cover 20+2 pages, pinned snapshots and
5+3 history, drafts, lifecycle, failure/retry, late reads, real revocation,
escaped metadata and desktop/390-pixel mobile layout. Actual build, deployment,
verification and cleanup results are recorded in
[claim-evidence-validation.txt](claim-evidence-validation.txt).

Explicit comparison of pinned evidence with current Source metadata:
[evidence-comparison.md](evidence-comparison.md).
