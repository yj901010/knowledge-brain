-- Local progress provenance only. All Group/Course/Enrollment IDs are external references.
ALTER TABLE group_course_progress ADD CONSTRAINT group_course_progress_scope UNIQUE(progress_id,group_id,space_id,course_id,participant_id);
CREATE TABLE course_progress_share (
 share_id uuid PRIMARY KEY, progress_id uuid NOT NULL, group_id uuid NOT NULL,space_id uuid NOT NULL,course_id uuid NOT NULL,participant_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),state text NOT NULL CHECK(state IN ('SHARED','WITHDRAWN')),
 policy_version bigint NOT NULL CHECK(policy_version>0),snapshot jsonb NOT NULL,
 FOREIGN KEY(progress_id,group_id,space_id,course_id,participant_id) REFERENCES group_course_progress(progress_id,group_id,space_id,course_id,participant_id),
 UNIQUE(space_id,course_id,participant_id)
);
CREATE INDEX course_progress_share_owner_page ON course_progress_share(group_id,space_id,course_id,policy_version,share_id) WHERE state='SHARED';
CREATE TABLE course_progress_share_revision (
 revision_id uuid PRIMARY KEY,share_id uuid NOT NULL REFERENCES course_progress_share(share_id),version bigint NOT NULL CHECK(version>0),
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,UNIQUE(share_id,version)
);
