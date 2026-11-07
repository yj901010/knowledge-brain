CREATE TABLE guest_identity (
  principal_id uuid PRIMARY KEY,
  credential_hash bytea NOT NULL UNIQUE CHECK (octet_length(credential_hash) = 32),
  created_at timestamptz NOT NULL,
  state text NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'CLAIMED', 'RETIRED'))
);
CREATE TABLE outbox_event (
  id uuid PRIMARY KEY,
  aggregatetype text NOT NULL,
  aggregateid uuid NOT NULL,
  type text NOT NULL,
  occurred_at timestamptz NOT NULL,
  trace_id text NOT NULL CHECK (trace_id ~ '^[0-9a-f]{32}$' AND trace_id <> repeat('0',32)),
  schema_group text NOT NULL,
  schema_artifact text NOT NULL,
  schema_version text NOT NULL,
  payload bytea NOT NULL CHECK (octet_length(payload) > 0)
);
CREATE INDEX outbox_event_occurred_at_idx ON outbox_event (occurred_at);
