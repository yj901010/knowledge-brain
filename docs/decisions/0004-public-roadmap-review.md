# Public Roadmap contribution and review

Accepted 2026-10-20 by the user: registered accounts explicitly submit the public
title, description and graph; operator-designated reviewers approve and merge.
Self-review and self-merge are forbidden. Personal progress, study sessions,
Notes, Concept/file evidence, attachments and private IDs are excluded.

Learning owns separate public roadmap snapshots, immutable revisions, proposals,
reviews and Outbox rows in its own database. It does not move or mutate private
roadmaps. CREATE allocates new public node/edge/roadmap IDs. UPDATE can retain only
IDs in the specified current public graph. A removed ID cannot return through a
later proposal. Submitted snapshots and rationale are publicly readable.

Space owns the existing public domain catalog and designated REVIEWER/ADMIN/OWNER
memberships. These memberships now authorize Learning's separate public actions.
Every request revalidates Identity and Space; no role is assigned by the browser.
Guest principals can browse; submission and review require registration.

SUBMITTED -> APPROVED -> MERGED or SUBMITTED -> REJECTED. Approval alone publishes
nothing. The contribution's strong pcN ETag and canonical base version serialize
review and merge. Canonical change, revision, review, contribution and both Outbox
events commit atomically. Immutable public events have new topics/artifacts;
existing private Roadmap events and Activity stream scopes remain unchanged.

PUBLIC visibility and REVIEWED verification are distinct fields. REVIEWED records
this graph review, without implying medical/expert certification or popularity.
Ratings/ranking, public roadmap adoption/private progress, withdrawals and reviewer
administration UI remain subsequent features.
