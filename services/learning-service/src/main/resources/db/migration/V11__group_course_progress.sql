-- All source Course/Study/Enrollment identifiers are immutable external references, no cross-service FK.
CREATE TABLE group_course_progress (
 progress_id uuid PRIMARY KEY,group_id uuid NOT NULL,space_id uuid NOT NULL,course_id uuid NOT NULL,participant_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),snapshot jsonb NOT NULL,UNIQUE(space_id,course_id,participant_id)
);
CREATE TABLE group_course_progress_revision (
 revision_id uuid PRIMARY KEY,progress_id uuid NOT NULL REFERENCES group_course_progress(progress_id),version bigint NOT NULL CHECK(version>0),
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,UNIQUE(progress_id,version)
);
