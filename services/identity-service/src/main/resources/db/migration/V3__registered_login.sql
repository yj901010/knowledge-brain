CREATE TABLE registered_account (
 principal_id uuid PRIMARY KEY,
 issuer text NOT NULL,
 subject text NOT NULL,
 created_at timestamptz NOT NULL,
 UNIQUE(issuer,subject)
);
CREATE TABLE registered_session (
 credential_hash bytea PRIMARY KEY CHECK(octet_length(credential_hash)=32),
 principal_id uuid NOT NULL REFERENCES registered_account(principal_id),
 access_token bytea NOT NULL,
 refresh_token bytea NOT NULL,
 access_expires_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,
 revoked_at timestamptz,
 claim_guest_id uuid NOT NULL REFERENCES guest_identity(principal_id)
);
CREATE INDEX registered_session_principal ON registered_session(principal_id);
CREATE TABLE oidc_login_flow (
 state_hash bytea PRIMARY KEY CHECK(octet_length(state_hash)=32),
 binding_hash bytea NOT NULL CHECK(octet_length(binding_hash)=32),
 guest_hash bytea NOT NULL CHECK(octet_length(guest_hash)=32),
 nonce text NOT NULL,
 verifier bytea NOT NULL,
 trace_id char(32) NOT NULL,
 expires_at timestamptz NOT NULL
);
CREATE INDEX oidc_login_expiry ON oidc_login_flow(expires_at);
ALTER TABLE guest_identity ADD COLUMN claimed_by uuid REFERENCES registered_account(principal_id);
