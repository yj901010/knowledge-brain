-- Immutable calculation basis for an existing immutable grade version, local only.
ALTER TABLE submission_grade_revision ADD CONSTRAINT grade_revision_exact_source UNIQUE(grade_id,version,submission_id,submission_version,actor_id);
ALTER TABLE assignment_rubric_revision ADD CONSTRAINT rubric_revision_exact_assignment UNIQUE(rubric_id,version,assignment_id);
CREATE TABLE submission_rubric_assessment (
 assessment_id uuid PRIMARY KEY,grade_id uuid NOT NULL,grade_version bigint NOT NULL CHECK(grade_version>0),
 submission_id uuid NOT NULL,submission_version bigint NOT NULL CHECK(submission_version>0),assignment_id uuid NOT NULL,
 study_id uuid NOT NULL,group_id uuid NOT NULL,space_id uuid NOT NULL,author_id uuid NOT NULL,grader_id uuid NOT NULL CHECK(grader_id<>author_id),
 rubric_id uuid NOT NULL,rubric_version bigint NOT NULL CHECK(rubric_version>0),assignment_version bigint NOT NULL CHECK(assignment_version>0),score integer NOT NULL CHECK(score BETWEEN 0 AND 100),snapshot jsonb NOT NULL,
 UNIQUE(grade_id,grade_version),
 FOREIGN KEY(grade_id,grade_version,submission_id,submission_version,grader_id) REFERENCES submission_grade_revision(grade_id,version,submission_id,submission_version,actor_id),
 FOREIGN KEY(submission_id,assignment_id,study_id,group_id,space_id,author_id) REFERENCES assignment_submission(submission_id,assignment_id,study_id,group_id,space_id,author_id),
 FOREIGN KEY(rubric_id,rubric_version,assignment_id) REFERENCES assignment_rubric_revision(rubric_id,version,assignment_id),
 FOREIGN KEY(assignment_id,assignment_version) REFERENCES study_assignment_revision(assignment_id,version)
);
