# Registered accounts and claimed Personal Spaces

Accepted by the user on 2026-10-08.

Keycloak authenticates registered accounts. Identity owns the immutable
application principal mapping and opaque browser sessions. Space owns the
ownership transfer. Guests remain application identities, not Keycloak users.

A registered principal may own multiple PERSONAL/PRIVATE Spaces. Claiming a
browser's guest Space preserves its Space, Note, Concept and Media IDs and does
not merge it with an existing account Space. The UI provides a Space selector.
One Space per original guest identity remains enforced, including after claim,
so replaying an older provisioning event cannot create a replacement Space.

Identity commits the guest claim and an Outbox event together. Space consumes
the event with a durable inbox, handles either ordering against guest creation,
and transfers only its own ownership/membership records. Personal data remains
private. Registered authentication never permits public canonical writes.

Registry keeps brain-platform and its existing issuer. User login uses a
separate brain-users Realm with authorization code, PKCE S256, exact callback
URLs and a confidential server client. Tokens remain on the Identity server;
the browser receives an HttpOnly/Secure opaque credential. Production uses
environment-specific public issuer and callback URLs.

Migration impact: Space V2 replaces the owner-based unique index with a guest-origin index. Old Space binaries that use ON CONFLICT(owner_principal_id) are not compatible after this migration. Use a forward fix/schema-aware image instead of rolling back to the old binary. No domain objects are deleted or copied.
