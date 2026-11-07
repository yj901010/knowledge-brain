# Markdown preview dependency review - 2026-10-09

Pinned react-markdown 10.1.0 and remark-gfm 4.0.1 were checked before implementation.
Both use MIT, allowing commercial use and redistribution with copyright/license
notice preservation. No raw HTML/MDX parser, remote rendering service, syntax
highlighting or external dataset is introduced. React tree rendering remains browser
code served in the existing static export; no new backend runtime is added.

Primary licenses:
- https://raw.githubusercontent.com/remarkjs/react-markdown/10.1.0/license
- https://raw.githubusercontent.com/remarkjs/remark-gfm/4.0.1/license

The package-lock change resolves 101 new package entries: 100 publisher MIT
license declarations and @ungap/structured-clone 1.4.0 ISC. ISC permits commercial
use/redistribution with copyright/permission notice retention. Primary source:
https://raw.githubusercontent.com/ungap/structured-clone/main/LICENSE
Resolved names, exact versions and declared licenses are recorded by the existing
infra/dependencies/graph-web.json build inventory. npm ci uses integrity-pinned
package-lock; no install scripts are introduced by the package-lock preparation.
The export workflow copies each installed non-development package's license/COPYING/
NOTICE files (including lowercase names) and combines their publisher text in
THIRD-PARTY-NOTICES.txt. Existing optional native LGPL tooling is not exported as
native runtime code and its previously documented obligations remain unchanged.
No medical terminology data is added or licensed by these software packages.
