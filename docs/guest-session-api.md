# Guest browser identity and authorized Space API

Current common-origin access uses [Gateway](gateway-api.md) on https://localhost:8443.
The two direct-service port-forwards below remain diagnostic access for operators;
the Gateway replaces the old Identity forward on port 8443. Internal service
introspection remains inaccessible through Gateway.

Identity owns opaque guest browser credentials. A cryptographically random 32-byte token is issued only as `__Host-brain_guest`, with Secure, HttpOnly, SameSite=Lax, Path=/, no Domain and 180-day Max-Age. PostgreSQL stores SHA-256, absolute expiration and revocation time, never the raw credential. The principal remains an application GUEST, without a Keycloak guest user. Returning with the valid cookie restores the same principal and does not emit another GuestIdentityCreated event or extend expiration. Missing cookie on creation creates a new guest; invalid/expired/revoked cookies return 401 without silently replacing its identity.

Creation commits the existing Identity guest and Outbox atomically; Space provisioning remains asynchronous through Kafka. `GET /api/spaces/personal` returns 202 with Retry-After: 1 until Space has committed its PRIVATE Space/OWNER membership. Once available it returns 200. Identity never queries Space's DB and Space never queries Identity's DB.

Space calls Identity's internal JSON REST introspection over HTTPS with hostname/CA verification, redirects disabled, bounded connect/read timeouts and a separate service credential. Only Identity resolves its credential hash. Space reads its own memberships for the returned principal; caller-supplied X-Principal-Id/X-User-Id headers are ignored. Unknown and unauthorized Space IDs both return 404. A missing or invalid guest credential returns 401. Identity outage returns 503 and fails closed, without a cached principal. Responses containing principal/Space/error data use Cache-Control: no-store. Other domain and management routes remain denied; servlet sessions are not created.

Browser mutations require the exact configured HTTPS Origin and reject missing/null/duplicate/other origins or cross-site Sec-Fetch-Site. Cookie authentication being stateless does not remove CSRF risk: Spring CSRF exceptions cover only these guarded mutations and the bearer-protected internal endpoint. Other routes retain protection/deny rules. Forwarded scheme headers are not trusted. Duplicate guest cookies are rejected. Introspection requires an independent credential even when a valid browser cookie is present. Origin, service authorization and guest credentials are validated independently.

Revocation expires the cookie and immediately denies the old credential in Identity and Space. Domain data is retained. Guests currently have no registered-account recovery; explicit revocation or loss of the browser credential loses access to that guest data until a later registered claiming/recovery flow exists. There is no automatic destructive data deletion or ownership transfer.

API contracts: [Identity](../contracts/openapi/identity-guest.yaml), [Space](../contracts/openapi/space-guest.yaml). Registered OIDC principal mapping and Space claiming are subsequent work; existing IDs/data do not need recreating. Hikari pools are bounded and Micrometer records creation/revocation; HTTP-originated trace IDs are propagated into the existing Outbox and downstream Space events. The full prescribed OpenTelemetry platform remains incomplete.

## Local Kubernetes deployment

These helpers require `k3d-knowledge-brain`. Provisioning creates/reuses a local CA, SAN/EKU/SKI/AKI-correct server certificates, a random introspection secret and owner DB credential copies without printing values. The CA private key remains in the operator namespace, outside application Pods. Only its public certificate is exported to ignored `.local/guest-api-ca.crt`. Service certificates last 60 days, CA 90 days; rotation remains an explicit operator task. The helper refuses a nearly expired CA. Do not disable certificate or hostname checks or replace these with production certificates implicitly.

```powershell
python ./scripts/Provision-GuestApiSecrets.py
python ./scripts/Build-LocalSpaceImage.py --service identity
python ./scripts/Build-LocalSpaceImage.py --service space
# Wait for both printed build Pods to become Ready.
python ./scripts/Import-LocalSpaceImage.py --service identity
python ./scripts/Import-LocalSpaceImage.py --service space
# Require every printed import Job to complete. Substitute the printed tag for each service.
helm upgrade --install identity-service ./infra/charts/brain-service --namespace brain-apps --kubeconfig .local/kubeconfig.yaml --kube-apiserver=https://127.0.0.1:6550 -f ./infra/services/identity-values.yaml -f ./infra/environments/local/identity-values.yaml --set-string image.tag=<IDENTITY_TAG> --wait --timeout 3m
helm upgrade --install space-service ./infra/charts/brain-service --namespace brain-apps --kubeconfig .local/kubeconfig.yaml --kube-apiserver=https://127.0.0.1:6550 -f ./infra/services/space-values.yaml -f ./infra/environments/local/space-values.yaml --set-string image.tag=<SPACE_TAG> --wait --timeout 3m
python ./scripts/Run-BackendValidation.py --cdc --space-kafka
python ./scripts/Run-GuestApiValidation.py
```

Identity ingress permits same-namespace Space/Gateway Pods and explicitly labeled validation Pods in brain-data. Introspection still requires its bearer secret. Namespace/Secret/label permissions belong to trusted infrastructure operators. Both applications run non-root with read-only root filesystems, no Kubernetes API token, service-owned DB credentials, HTTPS probes and mounted TLS certificates. Local and base configurations retain the same authentication/event architecture; production browser Origin must be explicitly supplied. Application endpoints remain ClusterIP-only; no public ingress is added here. Rate limiting/quotas, certificate/secret rotation automation, registered claiming, full observability and production delivery remain future tasks before public exposure.

The Jib build helper now accepts Identity or Space; its historic filename remains compatible. Remove each temporary image Pod, Service, ConfigMap and NetworkPolicy after imports. Local privileged containerd import is not production CD; GHCR/Argo CD still require the actual project registry/repository configuration.

## Manual local API use

Run these port-forwards in separate terminals; the APIs use HTTPS on service port 8080:

```powershell
kubectl --kubeconfig .local/kubeconfig.yaml --server=https://127.0.0.1:6550 -n brain-apps port-forward service/identity-service 8443:8080
kubectl --kubeconfig .local/kubeconfig.yaml --server=https://127.0.0.1:6550 -n brain-apps port-forward service/space-service 8444:8080
```

Then use the public local CA and an ignored cookie jar; do not print its contents:

```powershell
curl.exe --cacert .local/guest-api-ca.crt --cookie-jar .local/guest.cookies --header 'Origin: https://localhost:8443' --header 'Content-Type: application/json' --data '{}' https://localhost:8443/api/identity/guest-session
curl.exe --cacert .local/guest-api-ca.crt --cookie .local/guest.cookies https://localhost:8443/api/identity/guest-session
curl.exe --cacert .local/guest-api-ca.crt --cookie .local/guest.cookies https://localhost:8444/api/spaces/personal
```

The cookie is host-scoped, so the same localhost cookie applies to both TLS ports. Browser JavaScript has separate origin restrictions across ports; no permissive CORS is enabled. A Gateway route on a common origin and frontend are subsequent work. No Windows/browser root trust store is modified automatically.

## Verification

The real PostgreSQL tests cover random/hash-only tokens, durable restore, exact cookie attributes, expiry/revocation/retired state, Origin/HTTPS/duplicate-cookie rejection and the separate introspection credential. The HTTPS Job holds two browser cookies only in process memory and checks asynchronous private spaces, membership isolation, spoofed headers, invalid credentials, internal authentication and immediate logout across services. With `--restart`, it waits after phase 1 while an operator replaces both application Pods and signals `/tmp/restart.complete` through kubectl exec. An optional `/tmp/identity.down` signal checks Space's 503 response during Identity outage. Require explicit PASS logs before restoring/signaling; always restore local replicas afterward. The Job mounts only the public CA, not DB passwords or the introspection secret.

Evidence: [guest-session-runtime-validation.txt](guest-session-runtime-validation.txt). Security references reviewed: [OWASP session management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html), [OWASP CSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html), [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).
