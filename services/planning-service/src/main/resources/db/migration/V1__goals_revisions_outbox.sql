CREATE TABLE goal (
 goal_id uuid PRIMARY KEY,space_id uuid NOT NULL,visibility text NOT NULL CHECK(visibility='PRIVATE'),
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),version bigint NOT NULL CHECK(version>0),
 title text NOT NULL,description text NOT NULL,status text NOT NULL CHECK(status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED')),
 target_date date,created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,
 request_key char(32) NOT NULL,request_hash char(64) NOT NULL,UNIQUE(space_id,request_key)
);
CREATE INDEX goal_active_catalog ON goal(space_id,goal_id) WHERE state='ACTIVE';
CREATE TABLE goal_revision (
 revision_id uuid PRIMARY KEY,goal_id uuid NOT NULL REFERENCES goal(goal_id),version bigint NOT NULL CHECK(version>0),
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,UNIQUE(goal_id,version)
);
CREATE TABLE outbox_event (
 id uuid PRIMARY KEY,aggregatetype varchar(100) NOT NULL,aggregateid uuid NOT NULL,type varchar(100) NOT NULL,
 occurred_at timestamptz NOT NULL,trace_id varchar(32) NOT NULL,schema_group varchar(100) NOT NULL,
 schema_artifact varchar(100) NOT NULL,schema_version varchar(30) NOT NULL,payload bytea NOT NULL
);
