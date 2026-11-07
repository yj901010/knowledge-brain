ALTER TABLE space ADD COLUMN origin_guest_principal_id uuid;
UPDATE space SET origin_guest_principal_id=owner_principal_id WHERE kind='PERSONAL';
CREATE UNIQUE INDEX one_personal_space_per_guest_origin ON space(origin_guest_principal_id) WHERE kind='PERSONAL';
DROP INDEX one_personal_space_per_principal;
CREATE TABLE guest_space_claim (
 guest_principal_id uuid PRIMARY KEY,
 registered_principal_id uuid NOT NULL,
 event_id uuid UNIQUE NOT NULL,
 payload_hash bytea NOT NULL CHECK(octet_length(payload_hash)=32),
 trace_id char(32) NOT NULL,
 claimed_at timestamptz NOT NULL
);
