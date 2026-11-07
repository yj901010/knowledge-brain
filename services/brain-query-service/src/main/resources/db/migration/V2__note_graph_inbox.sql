CREATE TABLE note_graph_anchor (
 note_id uuid PRIMARY KEY, space_id uuid NOT NULL
);
CREATE TABLE note_graph_inbox (
 event_id uuid PRIMARY KEY, payload_hash bytea NOT NULL CHECK(octet_length(payload_hash)=32),
 received_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE note_graph_snapshot (
 note_id uuid NOT NULL REFERENCES note_graph_anchor(note_id),
 kind text NOT NULL CHECK(kind IN ('NOTE','LINKS')),
 version bigint NOT NULL CHECK(version>0),
 projected_version bigint NOT NULL DEFAULT 0 CHECK(projected_version>=0 AND projected_version<=version),
 fingerprint text NOT NULL CHECK(length(fingerprint)=64), payload bytea NOT NULL,
 PRIMARY KEY(note_id,kind)
);
CREATE INDEX note_graph_pending ON note_graph_snapshot(note_id,kind) WHERE projected_version<version;
