CREATE TABLE collaboration_group (
 group_id UUID PRIMARY KEY, creator_principal_id UUID NOT NULL, request_key CHAR(32) NOT NULL CHECK(request_key ~ '^[0-9a-f]{32}$'),
 title TEXT NOT NULL CHECK(char_length(title) BETWEEN 1 AND 200), description TEXT NOT NULL CHECK(octet_length(description)<=32768),
 state TEXT NOT NULL CHECK(state IN ('PENDING','ACTIVE')), space_id UUID UNIQUE,
 version BIGINT NOT NULL CHECK(version>0), created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 UNIQUE(creator_principal_id,request_key), CHECK((state='PENDING' AND space_id IS NULL AND version=1) OR (state='ACTIVE' AND space_id IS NOT NULL AND version=2))
);
CREATE INDEX group_creator_page ON collaboration_group(creator_principal_id,group_id);
CREATE TABLE group_revision (
 group_id UUID NOT NULL REFERENCES collaboration_group(group_id), version BIGINT NOT NULL,
 state TEXT NOT NULL, space_id UUID, actor_id UUID NOT NULL, occurred_at TIMESTAMPTZ NOT NULL,
 title TEXT NOT NULL, description TEXT NOT NULL, PRIMARY KEY(group_id,version)
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
