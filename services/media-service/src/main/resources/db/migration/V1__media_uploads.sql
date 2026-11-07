CREATE TABLE media (
 media_id uuid PRIMARY KEY,space_id uuid NOT NULL,actor_id uuid NOT NULL,
 state text NOT NULL CHECK(state IN ('PENDING','AVAILABLE')),
 version bigint NOT NULL CHECK(version>0),filename text NOT NULL,mime_type text NOT NULL,
 byte_size bigint NOT NULL CHECK(byte_size BETWEEN 1 AND 10485760),sha256 char(64) NOT NULL,
 object_version text,created_at timestamptz NOT NULL,completed_at timestamptz,
 CHECK((state='PENDING' AND object_version IS NULL AND completed_at IS NULL) OR (state='AVAILABLE' AND object_version IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX media_space_page ON media(space_id,media_id);
CREATE TABLE outbox_event (
 id uuid PRIMARY KEY,aggregatetype text NOT NULL,aggregateid uuid NOT NULL,type text NOT NULL,
 occurred_at timestamptz NOT NULL,trace_id char(32) NOT NULL,schema_group text NOT NULL,schema_artifact text NOT NULL,schema_version text NOT NULL,payload bytea NOT NULL
);
