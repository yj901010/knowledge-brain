CREATE TABLE note_search_inbox (
 event_id uuid PRIMARY KEY,
 payload_hash bytea NOT NULL CHECK (octet_length(payload_hash)=32),
 received_at timestamptz NOT NULL DEFAULT now()
);
-- Derived snapshots/queue only. Content owns canonical notes; no cross-service references/SQL.
CREATE TABLE note_search_snapshot (
 note_id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 version bigint NOT NULL CHECK (version>0),
 payload bytea NOT NULL,
 projected_version bigint NOT NULL DEFAULT 0 CHECK (projected_version>=0 AND projected_version<=version)
);
CREATE INDEX note_search_pending ON note_search_snapshot(note_id) WHERE projected_version<version;
