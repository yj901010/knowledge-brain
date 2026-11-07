CREATE TABLE space (
  space_id uuid PRIMARY KEY,
  kind text NOT NULL CHECK (kind IN ('PUBLIC','PERSONAL','GROUP','ORGANIZATION')),
  visibility text NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','PUBLIC')),
  owner_principal_id uuid NOT NULL,
  state text NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','RETIRED')),
  created_at timestamptz NOT NULL,
  CHECK (kind <> 'PERSONAL' OR visibility = 'PRIVATE')
);
CREATE UNIQUE INDEX one_personal_space_per_principal ON space(owner_principal_id) WHERE kind='PERSONAL';
CREATE TABLE space_membership (
  space_id uuid NOT NULL REFERENCES space(space_id),
  principal_id uuid NOT NULL,
  role text NOT NULL CHECK (role IN ('OWNER','ADMIN','EDITOR','CONTRIBUTOR','REVIEWER','MEMBER','VIEWER')),
  state text NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','RETIRED')),
  PRIMARY KEY (space_id,principal_id)
);
CREATE TABLE event_inbox (
  consumer_name text NOT NULL,
  event_id uuid NOT NULL,
  payload_hash bytea NOT NULL CHECK (octet_length(payload_hash)=32),
  space_id uuid NOT NULL REFERENCES space(space_id) DEFERRABLE INITIALLY DEFERRED,
  processed_at timestamptz NOT NULL,
  trace_id text NOT NULL CHECK (trace_id ~ '^[0-9a-f]{32}$' AND trace_id <> repeat('0',32)),
  PRIMARY KEY (consumer_name,event_id)
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
  payload bytea NOT NULL CHECK (octet_length(payload)>0)
);
CREATE INDEX outbox_event_occurred_at_idx ON outbox_event(occurred_at);
