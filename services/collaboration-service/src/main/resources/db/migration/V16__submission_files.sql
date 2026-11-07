-- All references are local. Media IDs are immutable external references, without cross-owner FK.
ALTER TABLE assignment_submission ADD CONSTRAINT submission_files_parent_tuple
 UNIQUE(submission_id,assignment_id,study_id,group_id,space_id,author_id);
CREATE TABLE submission_file_bundle (
 bundle_id uuid PRIMARY KEY, submission_id uuid NOT NULL, submission_version bigint NOT NULL,
 assignment_id uuid NOT NULL, study_id uuid NOT NULL, group_id uuid NOT NULL, space_id uuid NOT NULL,
 author_id uuid NOT NULL, snapshot jsonb NOT NULL,
 UNIQUE(submission_id,submission_version),
 FOREIGN KEY(submission_id,submission_version) REFERENCES assignment_submission_revision(submission_id,version),
 FOREIGN KEY(submission_id,assignment_id,study_id,group_id,space_id,author_id)
 REFERENCES assignment_submission(submission_id,assignment_id,study_id,group_id,space_id,author_id)
);
