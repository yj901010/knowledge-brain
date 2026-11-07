CREATE TABLE activity_inbox (
 event_id uuid PRIMARY KEY,space_id uuid NOT NULL,topic text NOT NULL,aggregate_id uuid NOT NULL,
 source_version bigint NOT NULL CHECK(source_version>0),payload_hash bytea NOT NULL,entry jsonb NOT NULL,
 projected boolean NOT NULL DEFAULT false,UNIQUE(topic,aggregate_id,source_version)
);
CREATE INDEX activity_pending ON activity_inbox(event_id) WHERE NOT projected;
CREATE TABLE activity_projection_state(singleton boolean PRIMARY KEY CHECK(singleton),seeded boolean NOT NULL DEFAULT false,rebuilding boolean NOT NULL DEFAULT false,ready boolean NOT NULL DEFAULT false);
INSERT INTO activity_projection_state(singleton) VALUES(true);
