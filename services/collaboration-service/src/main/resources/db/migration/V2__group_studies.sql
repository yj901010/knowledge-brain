-- Group FK stays inside Collaboration; Space/principal IDs are immutable external references.
ALTER TABLE collaboration_group ADD CONSTRAINT group_space_pair UNIQUE(group_id,space_id);
CREATE TABLE group_study (
 study_id uuid PRIMARY KEY, group_id uuid NOT NULL, space_id uuid NOT NULL,
 creator_id uuid NOT NULL, request_key char(32) NOT NULL CHECK(request_key ~ '^[0-9a-f]{32}$'),
 creation_title text NOT NULL, creation_description text NOT NULL,
 version bigint NOT NULL CHECK(version>0), state text NOT NULL CHECK(state IN ('PLANNED','ACTIVE','COMPLETED','RETIRED')),
 title text NOT NULL CHECK(char_length(title) BETWEEN 1 AND 200), snapshot jsonb NOT NULL,
 UNIQUE(group_id,creator_id,request_key),
 FOREIGN KEY(group_id,space_id) REFERENCES collaboration_group(group_id,space_id)
);
CREATE INDEX group_study_catalog ON group_study(group_id,space_id,study_id);
CREATE TABLE group_study_revision (
 revision_id uuid PRIMARY KEY, study_id uuid NOT NULL REFERENCES group_study(study_id),
 version bigint NOT NULL CHECK(version>0), actor_id uuid NOT NULL, occurred_at timestamptz NOT NULL,
 snapshot jsonb NOT NULL, UNIQUE(study_id,version)
);
