# Explicit sourced-draft authoring

The public Knowledge discovery workspace includes **출처 확인 초안 작성**.
It presents the 21 short Korean authoring drafts in
[`data/public-curation`](../data/public-curation/README.md): five initial medical
topics plus Backend and Computer Science, with eight primary sources and 33
unresolved local terms. These samples are separate from merged public knowledge.
They contain no private user material and confer no independent review, expert
qualification, official mapping or project-wide license.

## Authoring workflow

1. Select a topic and statement. Inspect its scope, qualifier notes, source title,
   section, retrieval/update dates and rights restrictions. NCI-derived previews
   retain the required nonendorsement text. Guests can inspect these drafts.
2. A registered contributor searches the selected PUBLIC Space or enters a known
   public Concept ID. Open the full current record and explicitly confirm its
   meaning and version before reusing it. A matching title alone is insufficient.
3. If a required term or Source is missing, prepare one ordinary CREATE proposal,
   inspect the exact payload/provenance, enter the public rationale and consent,
   then submit it. Wait for designated independent review and merge. The authoring
   UI has no approval or merge command.
4. Inspect/reuse the merged Source and the subject/predicate Concepts. A Claim
   proposal pins their current versions and uses the selected draft's typed
   literal. Source URL/title/rights and scoped wording remain visible.
5. Evidence and Qualifier proposals require an inspected merged Claim whose
   subject, predicate and literal match the selected draft. They retain the
   existing Claim/Source versions and ordinary public-record contracts. Reopen
   current canonical records after a dependency changes.
6. Submission receipts can refresh their ordinary review state. A reserved
   Concept/record ID from a pending proposal is not a canonical dependency: its
   canonical read must succeed before reuse. A lost response requires checking
   contribution history before another explicit submission.

The UI submits one proposal at a time. It rereads the principal, current public
catalog permission and every inspected canonical dependency before writing.
Existing Knowledge validation and independent review/CAS remain authoritative
for races after those reads. Private/foreign/retired records, changed versions,
unmerged IDs and wrong-domain or wrong-kind records cannot satisfy dependencies.
No backend importer, automatic publication, assessment, mapping, personal-data
copy or new domain endpoint is added.

Required reference versions are stored in each kind's ordinary `content` fields.
Additional authoring pins, such as a Claim's inspected Source, are checked before
submission and recorded in the public rationale; they do not add a new server
merge condition. A separate Evidence proposal establishes the canonical
Claim-to-Source relationship. `qualifierPins` remains exclusive to Verification.

## Scope and presentation

Draft inputs/pins clear when the actor, public Space or current permission changes.
Failed canonical reads clear candidates; aborted/late reads and old completed
writes cannot restore another scope or unlock its pending navigation. Source
links open explicitly with safe external-link attributes; the product does not
scrape source pages or fetch external images to fill drafts. Long metadata and
UUIDs wrap on small screens. Titles follow the canonical 200-code-point rule,
including astral Unicode, while descriptive character counts remain separate
from accessible field names.

The frontend mirror is byte-identical to the authoring source. Both local and
Linux exact-commit frontend builds call:

```powershell
python scripts/Sync-PublicCurationWeb.py
```

Only `--sync` explicitly refreshes the mirror. Stale/missing copies, paths outside
the project and material exceeding the 128 KiB authoring bound fail before build.
The existing offline content validator still checks authoring state, provenance,
references and volume limits. A successful build does not establish factual,
medical, professional or legal review.

## Verification

```powershell
python -m unittest discover -s tests/delivery -v
python scripts/Run-GraphWebValidation.py --public-curation
```

The browser selection includes real guest source/rights preview, real registered
Development-only canonical dependency inspection and a single unmerged Claim
proposal, plus deterministic stale/foreign/retired/late-response and scope cases.
Its temporary Development reviewer is a newly created test account, granted and
revoked through existing local operator tooling; no expert/official capability is
granted and no medical Claim is published. Scoped accessibility and desktop/mobile
captures are included. Actual results and limitations belong in the package's
validation record after the final image passes; definitions alone are not proof.

Final source/image/browser/preservation results and limits are recorded in
[the package validation](guidance-curation-validation.txt).
