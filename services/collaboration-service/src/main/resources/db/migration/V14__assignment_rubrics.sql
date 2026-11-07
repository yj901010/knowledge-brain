-- Group-shared assessment guidance. All references remain in Collaboration.
CREATE TABLE assignment_rubric (
 rubric_id uuid PRIMARY KEY,assignment_id uuid NOT NULL UNIQUE,study_id uuid NOT NULL,group_id uuid NOT NULL,space_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),assignment_version bigint NOT NULL CHECK(assignment_version>0),snapshot jsonb NOT NULL,
 UNIQUE(rubric_id,assignment_id),
 FOREIGN KEY(assignment_id,study_id,group_id,space_id) REFERENCES study_assignment(assignment_id,study_id,group_id,space_id),
 FOREIGN KEY(assignment_id,assignment_version) REFERENCES study_assignment_revision(assignment_id,version)
);
CREATE TABLE assignment_rubric_revision (
 revision_id uuid PRIMARY KEY,rubric_id uuid NOT NULL,version bigint NOT NULL CHECK(version>0),assignment_id uuid NOT NULL,
 assignment_version bigint NOT NULL CHECK(assignment_version>0),actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,
 UNIQUE(rubric_id,version),FOREIGN KEY(rubric_id,assignment_id) REFERENCES assignment_rubric(rubric_id,assignment_id),
 FOREIGN KEY(assignment_id,assignment_version) REFERENCES study_assignment_revision(assignment_id,version)
);
