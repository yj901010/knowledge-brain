# Project software license

On 2026-11-07 the project owner approved Apache-2.0 for the original project
code and public source delivery. The complete, unmodified license is in
[LICENSE](../LICENSE), with project attribution in [NOTICE](../NOTICE).

The root software license covers original project software, tests, build scripts
and project-authored deployment configuration. It does not replace a specific
license or attribution already attached to a third-party file or component.
Copyright remains with the respective contributors; the project notice uses a
collective contributor description, not an invented individual or organization.

## Separate component and data rights

- Existing third-party license and NOTICE files remain at their original paths.
  See the [dependency review](dependency-licenses.md),
  [dependency locks and notices](../infra/dependencies),
  [runtime legal material](../infra/runtime/legal) and
  [frontend notices](../apps/graph-web/THIRD-PARTY-NOTICES.txt).
- The [starter metadata](../data/public-starter/README.md) retains its recorded
  CC0-1.0 scope and provenance. The project software license does not replace it.
- The [21 factual authoring drafts](../data/public-curation/README.md) retain
  their source-specific rights notes, attribution and NCI nonendorsement text.
  They remain independently unreviewed authoring material. Publishing their
  source files does not merge them into canonical Knowledge, establish clinical
  verification, or relicense the cited NCI, PostgreSQL or IETF works.
- Project-code licensing does not grant redistribution rights to external
  terminology or clinical datasets, third-party images, logos or trademarks.
  It does not imply that private application data is licensed or public.

## Distribution records

The root license was downloaded as exact bytes from the
[Apache Software Foundation's official Apache-2.0 text](https://www.apache.org/licenses/LICENSE-2.0.txt).
It is 11,358 bytes; SHA256:
`cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`.
The standard appendix is preserved unchanged, including its example placeholders;
the actual project attribution is supplied separately in NOTICE.

The ASF's own source-header policy explicitly
[does not apply to non-ASF projects](https://www.apache.org/legal/src-headers.html).
This project does not claim ASF ownership, sponsorship or contribution agreements.
No existing third-party header is replaced with an ASF header.

Source and binary distributions must retain the project license and applicable
attributions together with their existing component notices. A source license
selection is not a claim that every transitive dependency or container is cleared
for redistribution. The actual image/release inventory and retained notices must
be checked for the artifact being published.

The shared Jib legal directory includes byte-identical copies named
`infra/runtime/legal/project-LICENSE.txt` and `project-NOTICE.txt`. Its existing
configuration places them under `/licenses/knowledge-brain` in application images.
Verify those entries in each actual published image; merely adding source files
does not prove their presence in an image built earlier.
