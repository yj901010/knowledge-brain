# Public knowledge factual drafts

`draft-pack-v1.json` contains 21 short Korean factual statements: three each for liver cancer, breast cancer, lung cancer, colorectal cancer, CML, Backend development and Computer Science. The technical subsets cover PostgreSQL transactions and HTTP method semantics. All statements, terms and source records remain **DRAFT, unpublished and independently unreviewed**. These files are local authoring material, not an API payload, an importer, a clinical reference or a canonical database export.

The 33 stable local term slugs and 21 statement slugs identify authoring items only. They are not domain IDs. Existing five cancer labels and 컴퓨터 과학 reuse the checked-in starter catalog as lookup hints. Other terms also require a current canonical-catalog lookup before proposing anything; matching a name does not establish matching meaning. The pack intentionally contains no resolved UUID, Space, version pin, operator role, expert qualification, verification assessment or official terminology mapping. It does not grant a license to the project or its other data.

Each statement includes its source title/URL, inspected section, retrieval date, evidence note, rights-policy reference, scope and qualifier notes. The source pages and reuse terms were checked on **2026-11-07**. Dates explicitly published by a source are retained where observed; null means no update date was captured, not that the source has never changed. No source page, external dataset, image, logo, graphic, table or code example is copied into this directory. The only verbatim policy text is the required NCI nonendorsement stored in the manifest. Statements are original concise Korean factual paraphrases.

Sources inspected:

- National Cancer Institute: [What Is Liver Cancer?](https://www.cancer.gov/types/liver/what-is-liver-cancer), introductory definition and Types of liver cancer.
- National Cancer Institute: [What Is Breast Cancer?](https://www.cancer.gov/types/breast/what-is-breast-cancer), definition and Glandular tissue body text. The credited © Terese Winslow image and its explanatory caption were excluded.
- National Cancer Institute: [Lung Cancer—Patient Version](https://www.cancer.gov/types/lung), opening overview.
- National Cancer Institute: [Colorectal Cancer—Patient Version](https://www.cancer.gov/types/colorectal), opening overview.
- National Cancer Institute: [Colon Cancer Treatment (PDQ®)–Patient Version](https://www.cancer.gov/types/colorectal/patient/colon-treatment-pdq), general definition and the explanation of metastatic tumor identity under Stage IV. Treatment instructions and illustrations were excluded.
- National Cancer Institute: [Chronic Myeloid Leukemia Treatment (PDQ®)–Patient Version](https://www.cancer.gov/types/leukemia/patient/cml-treatment-pdq), general disease information only. Treatment instructions, illustrations and captions were excluded.
- PostgreSQL Global Development Group: [PostgreSQL 18 Documentation, 3.4. Transactions](https://www.postgresql.org/docs/18/tutorial-transactions.html). Version 18 is pinned; the bank example and SQL examples are excluded.
- IETF: [RFC 9110: HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110.html), §§9.2.1–9.2.2. These are explanations of protocol facts, not copied RFC portions, translated excerpts or code components.

[NCI's reuse policy](https://www.cancer.gov/policies/copyright-reuse) permits reuse of unmarked text with NCI credit and original-title links; exceptions for copyright-marked material and graphics still apply. The PDQ pages also explain that partial or adapted material must not be presented as an official complete PDQ summary. These Korean paraphrases are adapted from NCI information, not official NCI/PDQ translations. The exact required nonendorsement sentence is stored once in the manifest's `rightsPolicies` entry with slug `nci-text`, field `nonendorsement`. Display and retain that sentence with NCI-derived previews or proposals.

The [PostgreSQL License](https://www.postgresql.org/about/licence/) covers the documentation and requires preservation of its notices when that documentation is copied. The pack supplies its own factual wording and links; reproducing source wording or examples later requires retaining the applicable copyright, permission and disclaimer notices. [IETF TLP 5.0](https://trustee.ietf.org/documents/trust-legal-provisions/tlp-5/) distinguishes permitted unmodified portions/translations from other modifications, while RFC 9110 also carries a pre-5378-material limitation. Those terms are recorded as restrictions, not as permission to relicense the RFC. No RFC prose or code is distributed here.

The pack contains no treatment choice, dosage, screening recommendation, individual diagnosis, professional credential, expert verification or official mapping. Clinical statements preserve their explicit population/modality limits. HTTP safe/idempotent statements concern the request's defined intent, not the absence of logs or identical responses. The PostgreSQL subset concerns local transactional table changes, not external calls or transactions across services. These limits do not replace independent factual or rights review.

Run the bounded offline checks with:

```powershell
python scripts/Validate-PublicCuration.py
python -m unittest discover -s tests/delivery -p test_public_curation.py -v
```

The validator checks draft boundaries, seven-topic coverage, stable/unique slugs, term/source references, evidence consistency, primary-source URL shapes, retained NCI wording, bounded size and a conservative per-source wording count. It enforces at most 128 KiB, 32 statements, 64 terms, 16 sources and **180 whitespace-separated words per source**, counting statement, literal object, scope/qualifier and evidence-note wording together. Repeated words are counted again. This count is a modest-volume guard, not a copyright rule or a medical/semantic validator. It makes no network requests and cannot certify that a source remains current, a statement is true or a use is legally cleared.

Before any future public write, an explicit user action must inspect the draft, source and rights note, select/reuse current Knowledge-owned IDs and versions, and submit the ordinary public proposal. Source, Claim, Evidence and Qualifier proposals must retain their existing version pins; separate designated independent review and atomic merge remain required. The pack must never be automatically imported, promoted, professionally verified or published. Private user content must never be used to fill missing drafts.


The [explicit authoring workflow](../../docs/public-curation.md) now implements current canonical-ID/version inspection and ordinary single-item proposal preparation in public discovery. Its [validation](../../docs/guidance-curation-validation.txt) does not promote or resolve this pack; independent review remains required.
