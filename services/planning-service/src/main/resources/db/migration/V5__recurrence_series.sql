ALTER TABLE schedule ADD CONSTRAINT schedule_id_space_unique UNIQUE(schedule_id,space_id);
CREATE TABLE recurrence (
 recurrence_id uuid PRIMARY KEY,space_id uuid NOT NULL,visibility text NOT NULL CHECK(visibility='PRIVATE'),
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),version bigint NOT NULL CHECK(version>0),title text NOT NULL,
 request_key text NOT NULL CHECK(request_key ~ '^[0-9a-f]{32}$'),request_hash text NOT NULL,
 created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,
 UNIQUE(space_id,request_key),UNIQUE(recurrence_id,space_id)
);
CREATE INDEX recurrence_catalog ON recurrence(space_id,state,recurrence_id);
CREATE TABLE recurrence_revision (
 revision_id uuid PRIMARY KEY,recurrence_id uuid NOT NULL REFERENCES recurrence(recurrence_id),version bigint NOT NULL,
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,UNIQUE(recurrence_id,version)
);
CREATE TABLE recurrence_occurrence (
 recurrence_id uuid NOT NULL,space_id uuid NOT NULL,ordinal integer NOT NULL CHECK(ordinal BETWEEN 1 AND 100),schedule_id uuid NOT NULL UNIQUE,
 PRIMARY KEY(recurrence_id,ordinal),
 FOREIGN KEY(recurrence_id,space_id) REFERENCES recurrence(recurrence_id,space_id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(schedule_id,space_id) REFERENCES schedule(schedule_id,space_id) DEFERRABLE INITIALLY DEFERRED
);
