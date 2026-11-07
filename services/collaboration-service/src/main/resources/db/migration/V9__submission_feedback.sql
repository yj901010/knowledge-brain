-- All provenance FKs are local to Collaboration; no principal/Space external FK.
ALTER TABLE assignment_submission ADD CONSTRAINT submission_feedback_scope
 UNIQUE(submission_id,assignment_id,study_id,group_id,space_id,author_id);
CREATE TABLE submission_feedback (
 feedback_id uuid PRIMARY KEY,submission_id uuid NOT NULL UNIQUE,assignment_id uuid NOT NULL,
 study_id uuid NOT NULL,group_id uuid NOT NULL,space_id uuid NOT NULL,author_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),submission_version bigint NOT NULL CHECK(submission_version>0),snapshot jsonb NOT NULL,
 UNIQUE(feedback_id,submission_id),
 FOREIGN KEY(submission_id,assignment_id,study_id,group_id,space_id,author_id)
  REFERENCES assignment_submission(submission_id,assignment_id,study_id,group_id,space_id,author_id),
 FOREIGN KEY(submission_id,submission_version) REFERENCES assignment_submission_revision(submission_id,version)
);
CREATE TABLE submission_feedback_revision (
 revision_id uuid PRIMARY KEY,feedback_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),submission_id uuid NOT NULL,submission_version bigint NOT NULL,
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,
 UNIQUE(feedback_id,version),
 FOREIGN KEY(feedback_id,submission_id) REFERENCES submission_feedback(feedback_id,submission_id),
 FOREIGN KEY(submission_id,submission_version) REFERENCES assignment_submission_revision(submission_id,version)
);
