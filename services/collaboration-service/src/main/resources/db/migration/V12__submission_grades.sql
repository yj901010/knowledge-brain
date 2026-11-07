-- Collaboration-owned local provenance; no cross-service FK or progress write.
CREATE TABLE submission_grade (
 grade_id uuid PRIMARY KEY,submission_id uuid NOT NULL UNIQUE,assignment_id uuid NOT NULL,
 study_id uuid NOT NULL,group_id uuid NOT NULL,space_id uuid NOT NULL,author_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),submission_version bigint NOT NULL CHECK(submission_version>0),
 grader_id uuid NOT NULL CHECK(grader_id<>author_id),score integer NOT NULL CHECK(score BETWEEN 0 AND 100),snapshot jsonb NOT NULL,
 UNIQUE(grade_id,submission_id),
 FOREIGN KEY(submission_id,assignment_id,study_id,group_id,space_id,author_id)
 REFERENCES assignment_submission(submission_id,assignment_id,study_id,group_id,space_id,author_id),
 FOREIGN KEY(submission_id,submission_version) REFERENCES assignment_submission_revision(submission_id,version)
);
CREATE TABLE submission_grade_revision (
 revision_id uuid PRIMARY KEY,grade_id uuid NOT NULL,version bigint NOT NULL CHECK(version>0),
 submission_id uuid NOT NULL,submission_version bigint NOT NULL,actor_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,UNIQUE(grade_id,version),
 FOREIGN KEY(grade_id,submission_id) REFERENCES submission_grade(grade_id,submission_id),
 FOREIGN KEY(submission_id,submission_version) REFERENCES assignment_submission_revision(submission_id,version)
);
