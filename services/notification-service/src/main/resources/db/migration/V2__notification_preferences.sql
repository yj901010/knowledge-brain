CREATE TABLE notification_preference (
 space_id uuid PRIMARY KEY, preference_id uuid NOT NULL UNIQUE,
 version bigint NOT NULL CHECK(version>0), in_app_enabled boolean NOT NULL,
 snapshot jsonb NOT NULL
);
CREATE TABLE notification_preference_revision (
 revision_id uuid PRIMARY KEY, preference_id uuid NOT NULL REFERENCES notification_preference(preference_id),
 version bigint NOT NULL CHECK(version>0), actor_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL, snapshot jsonb NOT NULL, UNIQUE(preference_id,version)
);
CREATE TABLE notification_suppression (
 suppression_id uuid PRIMARY KEY, space_id uuid NOT NULL, delivery_id uuid NOT NULL,
 source_event_id uuid NOT NULL UNIQUE REFERENCES notification_inbox(event_id),
 preference_id uuid NOT NULL, preference_version bigint NOT NULL,
 source_envelope bytea NOT NULL, snapshot jsonb NOT NULL,
 FOREIGN KEY(preference_id,preference_version) REFERENCES notification_preference_revision(preference_id,version),
 UNIQUE(space_id,delivery_id)
);
CREATE INDEX notification_suppression_catalog ON notification_suppression(space_id,suppression_id);
