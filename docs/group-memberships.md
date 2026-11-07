# Private Group invitations and membership

Registered users can issue and accept invitations, browse participating Groups, and manage Group membership at `/graph/groups`. Space owns invitations, membership roles, revisions and authorization. Collaboration remains the owner of immutable Group configuration. No private Note, learning progress or attachment is automatically shared. Shared public roadmap links are now available through separate Learning APIs; see [group-roadmaps.md](group-roadmaps.md). Group Study organization is available in [group-studies.md](group-studies.md); Course/Assignment/Discussion/Submission remain later slices.

## Initial policy

Only the active Group OWNER can issue/revoke invitations, list members/invitations/history, change MEMBER/VIEWER roles or remove a member. OWNER cannot be removed, demoted or leave in this slice; ownership transfer and ADMIN delegation are not exposed. MEMBER and VIEWER can read current Group configuration and their own membership, and explicitly leave. Personal-only content APIs reject all GROUP spaces regardless of Group role.

An invitation grants MEMBER or VIEWER. It expires after 24 hours and has one successful recipient. Anyone holding the code who has a registered account can explicitly accept it. The owner delivers the code manually; no email/contact messaging, directory lookup or automatic enrollment occurs. Codes never appear in URLs, logs, domain events, browser storage or GET responses. Creator POST responses are no-store; hide/close the code after safe delivery.

## Credentials and retries

Space derives a 256-bit code with HMAC-SHA256 over a domain-separated UUIDv7 invitation ID using a dedicated Space-only 32-byte Kubernetes Secret. The database stores only SHA-256(code). HTTPS POST issue requires a singleton 32-hex Idempotency-Key; exact replay returns the same invitation and code while pending/unexpired. Changed role with the same key is 409. Closed/expired issue replay returns metadata and no code. GET listing/history never returns a credential or hash. Preserve `brain-group-invitation-key` across deployment and replicas. Do not rotate silently. If the key changes, old codes remain acceptable against their saved hashes; old issuance replay refuses to invent a replacement code (503); revoke/reissue when necessary.

Acceptance serializes on the owning Space row. Concurrent recipients have exactly one winner. The same registered recipient can replay acceptance while their membership remains active; replay returns the current role/version and never overwrites later role changes. Exclusion or self-leave retires membership immediately. The old accepted code cannot reactivate it. A newly issued, explicitly accepted invitation can reactivate it with the next membership version.

Group membership updates and removal/leave require If-Match `"gmN"`; invitation revocation requires `"giN"`. Stale writes return 412 and create no new revision/event. Only local ACID transactions are used. Invitation acceptance, membership activation, both histories and both Outboxes commit or roll back together in Space. No network call is made while holding the database transaction; Identity resolution precedes mutation.

## Data and event contracts

V5 adds Group-only version use on existing Space membership, invitation rows/hashes and immutable invitation/member revisions. V6 preserves initial OWNER history when an older Space writer is briefly active during rolling deployment, using a local OWNER-insert audit trigger plus a one-time missing-baseline backfill. Existing creation stays idempotent and keeps one GroupSpaceCreated event. Previously applied migrations and published contracts are not rewritten.

New immutable Protobuf artifacts `space/group-invitation-events/1` and `space/group-membership-events/1` route to `space.group-invitation.v1` and `space.group-membership.v1`. Membership Kafka aggregate keys use the Space UUIDv7; snapshots identify `(space_id, principal_id, version)`. Invitation aggregate keys use Space-generated invitation UUIDv7. Every event carries a UUIDv7 event ID, actor, Space and trace. No credential/hash is propagated. `EXPIRED` is a read-time result of canonical expires_at, not a scheduled write or extra event. Consumers must deduplicate stable event IDs and respect snapshot versions.

Collaboration uses distinct, fresh Space participation capabilities for MEMBER/VIEWER/OWNER reads. The original creation/owned-list/detail/history endpoints remain OWNER/creator-only. `/api/groups/participating` and `/{id}/participation` return authorized ACTIVE Group configuration from Collaboration's own database in one bounded batch. Space IDs must match fresh grants. Missing/PENDING configurations are filtered; a page may be short/empty with an advancing Space candidate cursor. No foreign database joins or config copies are introduced.

## Runtime and verification

Before enabling writers: publish new contracts, apply Connect topic/producer ACLs and validator read ACLs, wait for KafkaTopic and brain-connect KafkaUser Ready. Apply `scripts/Provision-LocalGroupMembership.py` to preserve the Space-only key, then deploy Space, Collaboration and Gateway using base plus local Helm values. The existing Space Outbox connector routes the new aggregate types; do not reset offsets, slots or publications. If a writer ran before topic permissions existed, restart only failed Space connector tasks after readiness and verify backlog delivery. The tested recovery retained all Outbox records and offsets.

Strict bounded JSON, no query/body ambiguity, secure singleton browser credentials, same-origin mutations, exact Gateway routes, no-store responses and current registered principal/role checks apply. The owner UI retains issuance intent across response loss and safely retries it, shows recent audit history, and clears private views/codes when unavailable or hidden. The member UI explicitly accepts, refreshes current roles and clears inaccessible Group detail. No public publishing is added.

The service reuses existing Java 25 cryptography, JDBC/Flyway/Hikari, Protobuf and frontend dependencies. No new dependency version, dataset or AI functionality. Traceparent is shared by Identity calls and Outbox, including generated parents for direct requests; existing Gateway/Actuator/pool/readiness facilities apply. Full observability infrastructure remains separate platform work.

Validation evidence: `.local/group-memberships-validation.json` and `docs/group-memberships-validation.txt`.
