-- Retraction annotations belong to one immutable grade version, not future comments.
CREATE TABLE grade_retraction (
 retraction_id uuid PRIMARY KEY,grade_id uuid NOT NULL,grade_version bigint NOT NULL CHECK(grade_version>0),
 submission_id uuid NOT NULL,assignment_id uuid NOT NULL,study_id uuid NOT NULL,
 group_id uuid NOT NULL,space_id uuid NOT NULL,author_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),actor_id uuid NOT NULL CHECK(actor_id<>author_id),snapshot jsonb NOT NULL,
 UNIQUE(grade_id,grade_version),UNIQUE(retraction_id,grade_id,grade_version,author_id),
 FOREIGN KEY(grade_id,grade_version) REFERENCES submission_grade_revision(grade_id,version),
 FOREIGN KEY(grade_id,submission_id) REFERENCES submission_grade(grade_id,submission_id),
 FOREIGN KEY(submission_id,assignment_id,study_id,group_id,space_id,author_id)
  REFERENCES assignment_submission(submission_id,assignment_id,study_id,group_id,space_id,author_id)
);
CREATE TABLE grade_retraction_revision (
 revision_id uuid PRIMARY KEY,retraction_id uuid NOT NULL,version bigint NOT NULL CHECK(version>0),
 grade_id uuid NOT NULL,grade_version bigint NOT NULL,
 author_id uuid NOT NULL,actor_id uuid NOT NULL CHECK(actor_id<>author_id),occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,
 UNIQUE(retraction_id,version),
 FOREIGN KEY(retraction_id,grade_id,grade_version,author_id)
  REFERENCES grade_retraction(retraction_id,grade_id,grade_version,author_id)
);
