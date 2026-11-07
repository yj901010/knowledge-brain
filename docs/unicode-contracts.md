# Unicode input contracts

Identified natural-language title, reason and search fields now use the owning
API's Unicode code-point limit. Native HTML maxlength counts UTF-16 units, so its
physical ceiling permits astral characters while the submit predicate retains
the exact semantic limit. A code point is not a grapheme cluster. ASCII identifiers,
URLs, dates, keys, numbers and UTF-8 body budgets are not doubled.

The shared unicode-text module extracts the existing Java whitespace definition.
Java strip/isBlank intentionally differ from ECMAScript trim for NBSP, narrow NBSP
and BOM. Metadata names and filenames preserve those characters while retaining
their existing NFC, control/surrogate, byte and quota policies. Titles are checked
without inventing a new normalization or implicit save. Metadata replacement keeps
its ordinary new revision/event; the backend gains no new no-op behavior.

Coverage includes private Note/inline Concept/search; relevant private title
editors; Group/Study/Course/Assignment/Discussion; public Concept and typed-record
titles/reasons/fields; public128-point and canonical64-point search. Typed public
content still fits its separate12,000-byte JSON budget. The existing contracts,
fresh authority, independent review, CAS, immutable history and privacy remain.

```powershell
python scripts/Run-GraphWebValidation.py --unicode-contracts --no-images
```

The seven selected scenarios use actual owner APIs, accounts, projections and
MinIO. [Validation](unicode-contracts-validation.txt) records all checks, retained
failed attempts, exact images and preservation limits. See the broader
[requirement audit](v1-final-audit.md) for remaining V1 work.
