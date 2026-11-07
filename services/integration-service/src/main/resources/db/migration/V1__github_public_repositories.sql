CREATE TABLE github_repository (
 repository_id uuid PRIMARY KEY,space_id uuid NOT NULL,visibility text NOT NULL CHECK(visibility='PRIVATE'),
 external_id text NOT NULL,full_name text NOT NULL,url text NOT NULL,default_branch text NOT NULL,
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),version bigint NOT NULL CHECK(version>0),
 connected_by uuid NOT NULL,created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,last_synced_at timestamptz,
 request_actor uuid NOT NULL,request_key char(32) NOT NULL,request_hash char(64) NOT NULL,
 UNIQUE(space_id,request_actor,request_key),UNIQUE(repository_id,space_id)
);
CREATE UNIQUE INDEX github_active_source ON github_repository(space_id,external_id) WHERE state='ACTIVE';
CREATE INDEX github_repository_catalog ON github_repository(space_id,state,repository_id);
CREATE TABLE github_repository_revision (revision_id uuid PRIMARY KEY,repository_id uuid NOT NULL REFERENCES github_repository(repository_id),version bigint NOT NULL CHECK(version>0),actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,UNIQUE(repository_id,version));
CREATE TABLE github_commit (commit_id uuid PRIMARY KEY,space_id uuid NOT NULL,repository_id uuid NOT NULL,sha text NOT NULL,subject text NOT NULL,author_login text,committed_at timestamptz NOT NULL,imported_at timestamptz NOT NULL,imported_by uuid NOT NULL,snapshot jsonb NOT NULL,UNIQUE(repository_id,sha),FOREIGN KEY(repository_id,space_id) REFERENCES github_repository(repository_id,space_id));
CREATE INDEX github_commit_catalog ON github_commit(space_id,repository_id,commit_id);
CREATE TABLE github_sync (sync_id uuid PRIMARY KEY,space_id uuid NOT NULL,repository_id uuid NOT NULL,actor_id uuid NOT NULL,request_key char(32) NOT NULL,expected_version bigint NOT NULL CHECK(expected_version>0),snapshot jsonb NOT NULL,UNIQUE(repository_id,actor_id,request_key),FOREIGN KEY(repository_id,space_id) REFERENCES github_repository(repository_id,space_id));
CREATE TABLE outbox_event (id uuid PRIMARY KEY,aggregatetype varchar(100) NOT NULL,aggregateid uuid NOT NULL,type varchar(100) NOT NULL,occurred_at timestamptz NOT NULL,trace_id varchar(32) NOT NULL,schema_group varchar(100) NOT NULL,schema_artifact varchar(100) NOT NULL,schema_version varchar(30) NOT NULL,payload bytea NOT NULL);
