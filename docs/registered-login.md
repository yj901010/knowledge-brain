# Registered login and guest Personal Space claiming

The Graph UI offers **로그인 · 이 공간 연결** for the current guest Space.
Registered public Concept contribution/review now uses this common session;
see [public-contributions.md](public-contributions.md).
Keycloak's brain-users Realm handles registration/password authentication. The
Identity Service validates authorization code + PKCE, signed ID token and nonce,
maps the Keycloak subject to an immutable UUIDv7 principal and rotates the
browser's opaque credential. Guests are not Keycloak accounts.

Identity commits guest retirement, account/session state and
GuestSpaceClaimRequested to its own database/Outbox in one transaction. The
Space consumer stores a durable claim fingerprint, then changes only its own
Space ownership and membership. It retires the former guest's membership and
emits PersonalSpaceClaimed through Space's Outbox. Either event ordering with
guest provisioning is supported. Guest origin uniqueness prevents a delayed
guest creation event from replacing a claimed Space.

Space, Note, Concept, link, revision and Media identifiers remain unchanged.
No Content/Knowledge/Media rows or MinIO objects are copied or made public.
After claiming another browser's guest Space, an existing account owns both
Spaces. A selector in the Graph UI opens each separately; no automatic merge.
The current list is bounded at 100 Spaces. Account deletion, named Spaces,
cross-domain Activity timeline and public contribution/review remain future
work. Email verification and password reset are not enabled until mail delivery
is configured; no email ownership guarantee is made by this local setup.

Browser credentials use the existing __Host-brain_guest name for compatibility
with all domain services, but represent a GUEST or REGISTERED common principal.
No Keycloak token is sent to JavaScript or browser storage. Identity stores
opaque credential hashes and AES-256-GCM encrypted access/refresh tokens, bound
to the session and token purpose. Each registered authorization checks active
Keycloak state; refresh is serialized by an Identity-owned session row lock.
Sessions are bounded by the issued refresh-token expiry and a 12-hour maximum;
the current Realm has a 30-minute idle refresh lifetime. Logout revokes the
application credential immediately and attempts Keycloak refresh revocation.
Keycloak outages deny registered access; guests continue independently.

Login proof lasts five minutes, is bound to both a distinct Secure/HttpOnly
cookie and the original guest credential, and is consumed before code exchange.
A rejected exchange requires starting login again. Expired or revoked browser
credentials may start a fresh guest Space only through the explicit same-origin
start action. Existing server-side account Spaces remain preserved.

## Local use

Use two terminals in C:\dev\knowledge-brain:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Start-LocalGateway.ps1
```

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Start-LocalLogin.ps1
```

Open https://localhost:8443/graph, then choose the login/connect button. Both
HTTPS endpoints use .local/guest-api-ca.crt. The additional port is 8445. The
registered-login callback returns to 8443. Keep both forwards running.

The restricted brain-login HTTPS entry verifies the existing internal Keycloak
certificate and exposes only brain-users and login resources. Registry retains
brain-platform and its internal issuer. Its TLS certificate and credentials are
not rotated by user-login setup. The proxy uses no access logs, strips caller
forwarding/Authorization headers and does not expose Keycloak admin endpoints.

Provision-RegisteredLogin.py preserves existing client/encryption secrets and
configures only brain-users in a short-lived scoped operator Job. CA private
keys never enter application/validation Pods. Production supplies its own
public HTTPS origin, exact callback, operator-managed secrets/trust and ingress;
the service/event/database ownership architecture is the same.

## Contracts and verification

- contracts/openapi/identity-login.yaml and the compatible identity-guest API.
- identity.account.v1: identity/account-events V1, GuestSpaceClaimRequested.
- space.personal-space-claim.v1: space/personal-space-claim-events V1,
  PersonalSpaceClaimed.
- Registry rules: FULL validity and FULL_TRANSITIVE compatibility.
- Identity V3 and Space V2 migrations; own-database transactions only.
- PostgreSQL tests cover browser binding, replay/expiry, credential rotation,
  encrypted tokens, fresh introspection, account mapping, claim ordering,
  duplicate/conflicting claims and preservation of multiple Space IDs.
- Run-GraphWebValidation.py --login uses actual deployed verified HTTPS,
  Keycloak UI registration/login, private Note/Concept preservation, old guest
  denial, second-Space claiming/selection and logout. No TLS bypass or normal
  user's account credentials are used.

See decisions/0002-registered-personal-spaces.md for the accepted ownership
decision and registered-login-validation.txt for the concrete validation run.
