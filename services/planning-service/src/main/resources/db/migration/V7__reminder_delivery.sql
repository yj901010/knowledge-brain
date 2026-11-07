ALTER TABLE reminder ADD CONSTRAINT reminder_space_unique UNIQUE(reminder_id,space_id);
CREATE TABLE reminder_delivery (
 delivery_id uuid PRIMARY KEY,space_id uuid NOT NULL,reminder_id uuid NOT NULL,
 reminder_version bigint NOT NULL,schedule_id uuid NOT NULL,schedule_version bigint NOT NULL,
 state text NOT NULL CHECK(state IN ('PENDING','CANCELLED','SUPPRESSED','DISPATCHED')),
 version bigint NOT NULL CHECK(version>0),due_at timestamptz NOT NULL,snapshot jsonb NOT NULL,
 request_key text NOT NULL CHECK(request_key ~ '^[0-9a-f]{32}$'),request_hash text NOT NULL,
 dispatched_envelope bytea,UNIQUE(space_id,request_key),
 FOREIGN KEY(reminder_id,space_id) REFERENCES reminder(reminder_id,space_id),
 FOREIGN KEY(schedule_id,space_id) REFERENCES schedule(schedule_id,space_id)
);
CREATE UNIQUE INDEX reminder_delivery_one_pending ON reminder_delivery(reminder_id) WHERE state='PENDING';
CREATE INDEX reminder_delivery_catalog ON reminder_delivery(space_id,reminder_id,delivery_id);
CREATE INDEX reminder_delivery_due ON reminder_delivery(due_at,delivery_id) WHERE state='PENDING';
CREATE TABLE reminder_delivery_revision (
 revision_id uuid PRIMARY KEY,delivery_id uuid NOT NULL REFERENCES reminder_delivery(delivery_id),version bigint NOT NULL,
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,UNIQUE(delivery_id,version)
);
