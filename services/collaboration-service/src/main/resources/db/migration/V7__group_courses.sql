CREATE TABLE group_course (
 course_id uuid PRIMARY KEY, group_id uuid NOT NULL, space_id uuid NOT NULL,
 creator_id uuid NOT NULL, request_key char(32) NOT NULL CHECK(request_key ~ '^[0-9a-f]{32}$'),
 creation_title text NOT NULL, creation_description text NOT NULL,
 version bigint NOT NULL CHECK(version>0), state text NOT NULL CHECK(state IN ('DRAFT','ACTIVE','COMPLETED','RETIRED')),
 title text NOT NULL CHECK(char_length(title) BETWEEN 1 AND 200), snapshot jsonb NOT NULL,
 UNIQUE(group_id,creator_id,request_key),
 FOREIGN KEY(group_id,space_id) REFERENCES collaboration_group(group_id,space_id)
);
CREATE INDEX group_course_catalog ON group_course(group_id,space_id,course_id);
CREATE TABLE group_course_revision (
 revision_id uuid PRIMARY KEY, course_id uuid NOT NULL REFERENCES group_course(course_id),
 version bigint NOT NULL CHECK(version>0), actor_id uuid NOT NULL, occurred_at timestamptz NOT NULL,
 snapshot jsonb NOT NULL, UNIQUE(course_id,version)
);

ALTER TABLE group_course ADD CONSTRAINT course_group_space UNIQUE(course_id,group_id,space_id);
CREATE TABLE course_study (
 course_id uuid NOT NULL, group_id uuid NOT NULL, space_id uuid NOT NULL,
 position integer NOT NULL CHECK(position BETWEEN 0 AND 49),study_id uuid NOT NULL,study_version bigint NOT NULL,
 PRIMARY KEY(course_id,position),UNIQUE(course_id,study_id),
 FOREIGN KEY(course_id,group_id,space_id) REFERENCES group_course(course_id,group_id,space_id),
 FOREIGN KEY(study_id,group_id,space_id) REFERENCES group_study(study_id,group_id,space_id),
 FOREIGN KEY(study_id,study_version) REFERENCES group_study_revision(study_id,version)
);
