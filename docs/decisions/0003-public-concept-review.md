# Public Concept contribution and review

Accepted 2026-10-09: the user chose operator-designated reviewers and prohibited
self-approval. This implements PROJECT.md Contribution -> Review -> Merge.

Space owns PUBLIC visibility, domain catalog and reviewer membership. Registered
principals may propose changes; only active operator-designated REVIEWER, ADMIN
or OWNER memberships may approve, reject and merge. No browser grants roles.
An author cannot approve or merge their own contribution. Guest browsing remains
available. Public Concept CRUD never bypasses this workflow.

Knowledge owns immutable proposals, review history, canonical Concept revisions
and transactional Outbox events. Proposed CREATE/UPDATE text is an explicit
snapshot supplied by the contributor; no private Note/Concept is published or
copied implicitly. An UPDATE records the canonical base version. Approval does
not mutate canonical knowledge; merge requires an approved proposal and the same
base version. A stale proposal cannot overwrite a later revision.

Initial lifecycle: SUBMITTED -> APPROVED -> MERGED, or SUBMITTED -> REJECTED.
Proposals are immutable after submission. Rejection records a reason; a new
proposal is required for changes. Strong contribution ETags protect review and
merge concurrency. All mutations, audit rows and Outbox rows are one Knowledge
database transaction. No distributed transaction or cross-service SQL is used.

Operator provisioning targets Space's own database in an isolated, local-context
guarded Job. Reviewer UUIDs are application registered principal IDs, never
Keycloak subject IDs or user-supplied browser headers. Public Space IDs are fixed
UUIDv7 values seeded by Space migration; they do not use existing Personal IDs.

Migration impact: additive public catalog and Knowledge contribution/review
tables; Concept visibility expands to PRIVATE/PUBLIC. Existing private concepts,
revisions and API semantics remain unchanged. New public mutations are gated
until contracts/topics/ACLs and the new Space authority are ready. Medical
verification, terminology mappings, public Note references and public search/
graph projections remain separate subsequent features; review of Concept text
does not confer medical verification.
