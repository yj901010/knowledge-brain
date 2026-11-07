ALTER TABLE guest_identity ADD COLUMN credential_expires_at timestamptz;
UPDATE guest_identity SET credential_expires_at=created_at+interval '180 days';
ALTER TABLE guest_identity ALTER COLUMN credential_expires_at SET NOT NULL;
ALTER TABLE guest_identity ADD COLUMN credential_revoked_at timestamptz;
ALTER TABLE guest_identity ADD CONSTRAINT guest_credential_lifetime CHECK (credential_expires_at > created_at);
