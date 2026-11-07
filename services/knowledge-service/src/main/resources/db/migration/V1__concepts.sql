CREATE TABLE concept (
  concept_id uuid PRIMARY KEY,
  space_id uuid NOT NULL,
  visibility text NOT NULL DEFAULT 'PRIVATE' CHECK (visibility = 'PRIVATE'),
  state text NOT NULL CHECK (state IN ('ACTIVE','RETIRED')),
  version bigint NOT NULL CHECK (version > 0),
  title text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200 AND btrim(title) <> ''),
  description text NOT NULL CHECK (octet_length(description) <= 32768),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);
CREATE INDEX concept_space_active_idx ON concept(space_id, concept_id) WHERE state='ACTIVE';
CREATE TABLE concept_revision (
  revision_id uuid PRIMARY KEY,
  concept_id uuid NOT NULL REFERENCES concept(concept_id),
  version bigint NOT NULL CHECK (version > 0),
  title text NOT NULL,
  description text NOT NULL,
  state text NOT NULL CHECK (state IN ('ACTIVE','RETIRED')),
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  UNIQUE (concept_id, version)
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
