ALTER TABLE goal ADD CONSTRAINT goal_space_identity UNIQUE(goal_id,space_id);
CREATE TABLE task (
 task_id uuid PRIMARY KEY,space_id uuid NOT NULL,visibility text NOT NULL CHECK(visibility='PRIVATE'),
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),version bigint NOT NULL CHECK(version>0),
 title text NOT NULL,description text NOT NULL,status text NOT NULL CHECK(status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED')),
 due_date date,goal_id uuid,created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,
 request_key char(32) NOT NULL,request_hash char(64) NOT NULL,UNIQUE(space_id,request_key),
 CONSTRAINT task_same_space_goal FOREIGN KEY(goal_id,space_id) REFERENCES goal(goal_id,space_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX task_catalog ON task(space_id,state,task_id);
CREATE INDEX task_goal_catalog ON task(space_id,goal_id,state,task_id);
CREATE TABLE task_revision (
 revision_id uuid PRIMARY KEY,task_id uuid NOT NULL REFERENCES task(task_id),version bigint NOT NULL CHECK(version>0),
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,UNIQUE(task_id,version)
);
