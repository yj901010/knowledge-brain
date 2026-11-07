# Pinned evidence and current Source comparison

AGENTS.md and PROJECT.md were read before implementation. This adds an explicit
read to the existing Knowledge-owned Source API from the read-only Claim evidence
panel. Existing ownership, private Space/common principal authority, canonical
revisions and immutable event contracts remain compatible. No new dependency,
API, migration or canonical mutation is introduced.

In **사용 관계 근거 확인**, select a Citation or one of its historical revisions,
then choose **현재 출처와 비교**. A table compares the selected pin's Source version,
title, URL, exact citation text and license memo against a freshly read current
Source. Each field is labelled 동일 or 다름 by exact value comparison; formatting,
whitespace and URL spelling are not normalized. Empty and null values are labelled.
The current column means the successful read at the button click, not a live feed.
No pin is replaced, no verification state changes, and no content is fetched from
the Source URL. The comparison renders URL metadata as escaped text.

Current Source reads require HTTPS and fresh existing CONCEPT_READ authority in
the PERSONAL PRIVATE Space. Source ID, Space, private ACTIVE state, metadata,
positive safe version, dates and the strong srcN item ETag are checked before
display. Contract: [personal-sources.yaml](../contracts/openapi/personal-sources.yaml).
The displayed Citation still follows [claim-citations.yaml](../contracts/openapi/claim-citations.yaml).

The comparison mounts independently for each Citation/revision/pin identity.
Changing a Citation, historical revision, Claim, role, Concept or Space, or closing
the panel aborts its eight-second bounded request and discards late responses.
Parent Claim/Concept mode changes preserve a still-selected pin. A new comparison
clears its old current column. Failure or malformed metadata clears that column
and permits retry. Source retirement/404 keeps the pin and known Citation history.
401/403 clears private parent state. No browser storage or automatic polling is
added; unrelated editor drafts remain independent.

Use the existing Gateway static build/import flow and
`Install-LocalClaimUsages.py --gateway-only --helm PATH`. The unchanged Knowledge
service continues to serve its existing APIs. Focused actual-browser validation:
`Run-GraphWebValidation.py --evidence-comparison-only`; the full `--public` suite
includes it. Delivery evidence is in
[evidence-comparison-validation.txt](evidence-comparison-validation.txt).
