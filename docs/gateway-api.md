# Common HTTPS API Gateway

Gateway exposes the existing Identity guest-session and Space read APIs on one
HTTPS origin. It is an independent Java 25/Spring MVC service with no database,
Kafka credential or Identity introspection key. Identity still owns credential
validation; Space still owns authorization. No service boundary or consistency
decision changes. Existing JDK/Spring and reviewed Jib dependencies are reused.

Routes are an explicit method/path allowlist: GET/POST guest-session, POST revoke,
GET personal Space and GET a canonical UUIDv7 Space ID. Internal introspection,
other domain methods/paths and management data do not route. Gateway serves its
own health probes. Backend origins are fixed configured HTTPS URLs, never client
input. Query strings are rejected. No permissive CORS, redirects or application
retry policy is introduced.

Gateway forwards only Cookie, Origin, Sec-Fetch-Site, Content-Type, a validated or
generated W3C traceparent and fixed JSON Accept. Authorization, principal/user,
Forwarded, Host and hop-by-hop headers are not copied from callers. Browser POSTs
require HTTPS, exact configured Origin, acceptable fetch metadata and JSON.
The stateless security chain ignores Spring CSRF only for those guarded POSTs.
Request bodies are capped at 1 KiB for these guest APIs; future Note routes need
their own larger limits. Forwarded header values are capped and response bodies
are bounded to 64 KiB. A cancellation deadline covers the complete upstream
response, including a stalled body, in addition to connect/request timeouts.

Backend TLS validates the mounted public CA and hostname. Status/body and
Set-Cookie/Content-Type/Retry-After are preserved; other backend headers are not
copied. Responses are no-store and carry X-Trace-Id. Trace IDs propagate through
Identity Outbox, Kafka and Space Outbox. Logs contain service/status/trace, never
cookie, credential, request body or private note data. Micrometer counts proxied
responses. Full OpenTelemetry collectors/dashboards remain platform work.

Connection failures return generic 502, the complete-response deadline returns
504, and a backend's authentication-unavailable 503 is preserved. These fail
closed; Gateway readiness remains independent of downstream availability.
Internal HTTP clients are not configured to cache cookies or principals.

## Local deployment

Require the existing Identity/Space/CDC platform and k3d-knowledge-brain context.

```powershell
python ./scripts/Provision-GuestApiSecrets.py
python ./scripts/Run-BackendValidation.py --gateway
python ./scripts/Build-LocalSpaceImage.py --service gateway
# Wait for the printed build Pod to be Ready.
python ./scripts/Import-LocalSpaceImage.py --service gateway
# Require all printed import Jobs to complete, then substitute the printed tag:
helm upgrade --install gateway-service ./infra/charts/brain-service --namespace brain-apps --kubeconfig .local/kubeconfig.yaml --kube-apiserver=https://127.0.0.1:6550 -f ./infra/services/gateway-values.yaml -f ./infra/environments/local/gateway-values.yaml --set-string image.tag=<GATEWAY_TAG> --wait --timeout 3m
python ./scripts/Run-GuestApiValidation.py --gateway
python ./scripts/Run-BackendValidation.py --cdc --space-kafka
```

Space's Helm values now also enable the existing ingress NetworkPolicy: only
same-namespace Gateway/Space Pods and labeled brain-data validation Pods can
connect. Apply current Space values with its existing explicit image tag. Identity
already permits Gateway under the same policy. Namespace/label permissions are
for trusted operators. Gateway has only its own TLS Secret and public CA mount;
it runs non-root with a read-only root filesystem and no Kubernetes API token.
Base/local use the same TLS, routing and headers, with different resources and
replicas. Production browser Origin and image repository/tag must be explicitly
provided. GHCR/Argo CD, public ingress, quotas and full observability are not
claimed by this local milestone.

Remove temporary build Pod/Service/ConfigMap/NetworkPolicy after imports. The
historic local build/import helpers now accept identity, space or gateway.

## One local address

If an old direct Identity port-forward occupies 8443, stop that forward first.
Run this Gateway forward in a separate terminal:

Alternatively run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/Start-LocalGateway.ps1`. This helper checks the local context and Gateway readiness, binds only 127.0.0.1, and exits on Ctrl+C. The execution-policy override applies only to that process; no machine policy is changed.

```powershell
kubectl --kubeconfig .local/kubeconfig.yaml --server=https://127.0.0.1:6550 -n brain-apps port-forward service/gateway-service 8443:8080
```

Both APIs now use the same HTTPS origin and cookie:

```powershell
curl.exe --cacert .local/guest-api-ca.crt --cookie-jar .local/guest.cookies --header 'Origin: https://localhost:8443' --header 'Content-Type: application/json' --data '{}' https://localhost:8443/api/identity/guest-session
curl.exe --cacert .local/guest-api-ca.crt --cookie .local/guest.cookies https://localhost:8443/api/identity/guest-session
curl.exe --cacert .local/guest-api-ca.crt --cookie .local/guest.cookies https://localhost:8443/api/spaces/personal
```

Pending Space provisioning returns 202/Retry-After; repeat the read until 200.
The secure host-only cookie needs no domain rewrite. No browser/system CA trust
store is modified automatically. The frontend and Markdown Note APIs are next;
the accepted ten note capabilities are recorded in PROJECT.md and
[personal-notes-roadmap.md](personal-notes-roadmap.md).

`Run-GuestApiValidation.py --gateway --restart` additionally holds two cookies in
memory while an operator checks Identity/Space outages, restores local replicas
and replaces Gateway. Signal identity.down/space.down only during those outages,
then restart.complete only after all three services are Ready. Require PASS logs;
always restore replicas. Evidence: [gateway-runtime-validation.txt](gateway-runtime-validation.txt).

## Markdown Note extension

Gateway now forwards the documented Content Note paths, PUT/DELETE, strong If-Match,
ETag/Location and validated Note pagination queries. Guest API limits stay unchanged;
Content uses 256 KiB JSON requests and 2 MiB bounded responses for revision pages.
See [markdown-notes.md](markdown-notes.md) and the composed Gateway OpenAPI contract.
The private Space content-authorization endpoint is denied at Gateway.
