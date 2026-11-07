ALTER TABLE task ADD CONSTRAINT task_space_identity UNIQUE(task_id,space_id);
CREATE TABLE schedule (
 schedule_id uuid PRIMARY KEY,space_id uuid NOT NULL,visibility text NOT NULL CHECK(visibility='PRIVATE'),
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),version bigint NOT NULL CHECK(version>0),title text NOT NULL,description text NOT NULL,
 starts_at timestamptz NOT NULL,ends_at timestamptz NOT NULL CHECK(ends_at>starts_at),time_zone text NOT NULL,task_id uuid,
 created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,request_key char(32) NOT NULL,request_hash char(64) NOT NULL,UNIQUE(space_id,request_key),
 CONSTRAINT schedule_same_space_task FOREIGN KEY(task_id,space_id) REFERENCES task(task_id,space_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX schedule_agenda ON schedule(space_id,state,starts_at,schedule_id);
CREATE INDEX schedule_task_agenda ON schedule(space_id,task_id,state,starts_at,schedule_id);
CREATE TABLE schedule_revision (
 revision_id uuid PRIMARY KEY,schedule_id uuid NOT NULL REFERENCES schedule(schedule_id),version bigint NOT NULL CHECK(version>0),
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,UNIQUE(schedule_id,version)
);
