-- Course participation is Collaboration-owned; current Group role remains Space-owned.
CREATE TABLE course_enrollment (
 enrollment_id uuid PRIMARY KEY,course_id uuid NOT NULL,group_id uuid NOT NULL,space_id uuid NOT NULL,participant_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),state text NOT NULL CHECK(state IN ('ENROLLED','WITHDRAWN')),snapshot jsonb NOT NULL,
 UNIQUE(course_id,participant_id),UNIQUE(enrollment_id,course_id),
 FOREIGN KEY(course_id,group_id,space_id) REFERENCES group_course(course_id,group_id,space_id)
);
CREATE INDEX course_enrollment_catalog ON course_enrollment(course_id,enrollment_id);
CREATE TABLE course_enrollment_revision (
 revision_id uuid PRIMARY KEY,enrollment_id uuid NOT NULL,version bigint NOT NULL CHECK(version>0),
 course_id uuid NOT NULL,course_version bigint NOT NULL CHECK(course_version>0),actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,
 UNIQUE(enrollment_id,version),FOREIGN KEY(enrollment_id,course_id) REFERENCES course_enrollment(enrollment_id,course_id),
 FOREIGN KEY(course_id,course_version) REFERENCES group_course_revision(course_id,version)
);
