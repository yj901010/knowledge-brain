-- All foreign keys remain inside Collaboration. External Space/principal are immutable IDs.
CREATE TABLE study_assignment (
 assignment_id uuid PRIMARY KEY, study_id uuid NOT NULL, group_id uuid NOT NULL, space_id uuid NOT NULL,
 creator_id uuid NOT NULL, request_key char(32) NOT NULL CHECK(request_key ~ '^[0-9a-f]{32}$'),
 creation_title text NOT NULL, creation_description text NOT NULL,
 version bigint NOT NULL CHECK(version>0), state text NOT NULL CHECK(state IN ('DRAFT','OPEN','CLOSED','RETIRED')),
 title text NOT NULL CHECK(char_length(title) BETWEEN 1 AND 200), snapshot jsonb NOT NULL,
 UNIQUE(study_id,creator_id,request_key),
 FOREIGN KEY(study_id,group_id,space_id) REFERENCES group_study(study_id,group_id,space_id)
);
CREATE INDEX study_assignment_catalog ON study_assignment(study_id,group_id,space_id,assignment_id);
CREATE TABLE study_assignment_revision (
 revision_id uuid PRIMARY KEY, assignment_id uuid NOT NULL REFERENCES study_assignment(assignment_id),
 version bigint NOT NULL CHECK(version>0), actor_id uuid NOT NULL, occurred_at timestamptz NOT NULL,
 snapshot jsonb NOT NULL, UNIQUE(assignment_id,version)
);
