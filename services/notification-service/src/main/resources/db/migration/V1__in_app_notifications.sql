CREATE TABLE notification_inbox (
 event_id uuid PRIMARY KEY,payload_hash text NOT NULL,received_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE notification (
 notification_id uuid PRIMARY KEY,space_id uuid NOT NULL,delivery_id uuid NOT NULL,source_event_id uuid NOT NULL UNIQUE,
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),is_read boolean NOT NULL,version bigint NOT NULL CHECK(version>0),
 source_envelope bytea NOT NULL,snapshot jsonb NOT NULL,UNIQUE(space_id,delivery_id)
);
CREATE INDEX notification_catalog ON notification(space_id,state,notification_id);
CREATE INDEX notification_unread ON notification(space_id,state,is_read,notification_id);
CREATE TABLE notification_revision (
 revision_id uuid PRIMARY KEY,notification_id uuid NOT NULL REFERENCES notification(notification_id),version bigint NOT NULL,
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,UNIQUE(notification_id,version)
);
CREATE TABLE notification_runtime (singleton boolean PRIMARY KEY CHECK(singleton),seeded boolean NOT NULL);
INSERT INTO notification_runtime VALUES (true,false);
CREATE TABLE outbox_event (
 id uuid PRIMARY KEY,aggregatetype text NOT NULL,aggregateid uuid NOT NULL,type text NOT NULL,
 timestamp timestamptz NOT NULL,trace_id text NOT NULL,schema_group text NOT NULL,
 schema_artifact text NOT NULL,schema_version text NOT NULL,payload bytea NOT NULL
);
