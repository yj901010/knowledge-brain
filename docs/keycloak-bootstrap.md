# Keycloak and Registry authentication

Keycloak 26.8.0 is pinned from the official release. Apache-2.0 permits commercial use and redistribution with applicable license/NOTICE obligations. Runtime image OS/transitive notices and vulnerability inventory remain separate distribution work.

Sources: https://github.com/keycloak/keycloak/releases/tag/26.8.0 ; https://github.com/keycloak/keycloak/blob/26.8.0/LICENSE.txt ; https://www.keycloak.org/server/containers ; https://www.keycloak.org/server/importExport ; https://www.keycloak.org/observability/health .

Keycloak exclusively owns authentication and its brain_keycloak database. This step provisions the brain-platform infrastructure realm, not application guest users, account registration or Space authorization. Guest principals remain a later Identity/Space implementation.

Both local/base use production-mode `start`, PostgreSQL, TLS and clustered cache discovery; local only reduces replicas/resources. The official image performs its build at startup; a prebuilt optimized image remains delivery work. Local certificate is self-signed, valid 90 days for brain-keycloak.brain-data.svc, with explicit Registry trust; never disable certificate or hostname validation. Production requires real hostname and externally managed certificate. Registry-facing API is an internal ClusterIP; browser login, public issuer/ingress and frontend redirect URIs are not provisioned.

The realm imports registry-api as a resource client and confidential service accounts registry-publisher (sr-developer), registry-reader (sr-readonly), registry-unprivileged (no Registry role). Password grants/browser flows are disabled for these accounts. Audience mapper includes registry-api; scope mappings limit roles. sr-admin is defined but not granted to these accounts. Generated credentials are stored in Kubernetes Secrets, never Helm values or source. bootstrap-admin is for initial platform setup; production requires bootstrap account retirement and a managed admin lifecycle.

Startup import skips an existing realm; editing the JSON or rotating a Secret does not update existing realm clients. Future changes must use explicit reviewed Admin API migration and synchronized client rotation. Local generated TLS Secret is not overwritten on retries. Certificate renewal requires updating the public trust Secret and rolling both consumers; do not silently rotate it.

PostgreSQL owns its admin/13 database credentials in brain-data. The initialization volume runs once. Keycloak mounts only its own DB password; Registry has no database credentials and uses KafkaSQL. Keycloak pool is limited to 10 connections per replica against its role limit of 30; larger replica counts require a measured connection-budget change.

Registry uses OIDC issuer https://brain-keycloak.brain-data.svc:8443/realms/brain-platform, explicit TLS trust, required registry-api audience and realm_access/roles mapping. Registry Kafka access remains mTLS with scoped ACLs. Existing Registry compatibility rules are not installed by this step; Protobuf validity/compatibility, trace propagation, public routing, backup/restore and failure tests remain pending.

Live validation results are recorded separately in auth-runtime-validation.txt. Replica/HA scaling is a configuration baseline, not proven multi-node availability. Do not uninstall or delete PVCs as recovery. Keep Secrets and database backups outside Git and logs.

Validation Jobs use Python 3.13.7-alpine (PSF-2.0, https://docs.python.org/3/license.html). They contain no plaintext credentials and mount only service-client credentials and the public TLS certificate. Run scripts/Run-RegistryAuthValidation.py from a Python environment; kubectl must be accessible. Jobs have fixed names and are retained for 24 hours; remove only a completed validation Job before rerunning it. The runner accepts an artifact ID as its optional argument to verify persistence after a Registry rollout. No production browser login or application user registration was added.
