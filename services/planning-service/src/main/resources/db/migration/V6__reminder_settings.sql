CREATE TABLE reminder (
 reminder_id uuid PRIMARY KEY,space_id uuid NOT NULL,schedule_id uuid NOT NULL,
 visibility text NOT NULL CHECK(visibility='PRIVATE'),state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),
 version bigint NOT NULL CHECK(version>0),minutes_before integer NOT NULL CHECK(minutes_before BETWEEN 0 AND 10080),
 request_key text NOT NULL CHECK(request_key ~ '^[0-9a-f]{32}$'),request_hash text NOT NULL,
 created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,UNIQUE(space_id,request_key),
 FOREIGN KEY(schedule_id,space_id) REFERENCES schedule(schedule_id,space_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX reminder_catalog ON reminder(space_id,state,reminder_id);
CREATE INDEX reminder_schedule_catalog ON reminder(space_id,state,schedule_id,reminder_id);
CREATE TABLE reminder_revision (
 revision_id uuid PRIMARY KEY,reminder_id uuid NOT NULL REFERENCES reminder(reminder_id),version bigint NOT NULL,
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,UNIQUE(reminder_id,version)
);
