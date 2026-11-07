-- Retraction annotations belong to one immutable feedback version, not future comments.
CREATE TABLE feedback_retraction (
 retraction_id uuid PRIMARY KEY,feedback_id uuid NOT NULL,feedback_version bigint NOT NULL CHECK(feedback_version>0),
 submission_id uuid NOT NULL,assignment_id uuid NOT NULL,study_id uuid NOT NULL,
 group_id uuid NOT NULL,space_id uuid NOT NULL,author_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),snapshot jsonb NOT NULL,
 UNIQUE(feedback_id,feedback_version),UNIQUE(retraction_id,feedback_id,feedback_version),
 FOREIGN KEY(feedback_id,feedback_version) REFERENCES submission_feedback_revision(feedback_id,version),
 FOREIGN KEY(feedback_id,submission_id) REFERENCES submission_feedback(feedback_id,submission_id),
 FOREIGN KEY(submission_id,assignment_id,study_id,group_id,space_id,author_id)
  REFERENCES assignment_submission(submission_id,assignment_id,study_id,group_id,space_id,author_id)
);
CREATE TABLE feedback_retraction_revision (
 revision_id uuid PRIMARY KEY,retraction_id uuid NOT NULL,version bigint NOT NULL CHECK(version>0),
 feedback_id uuid NOT NULL,feedback_version bigint NOT NULL,
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,
 UNIQUE(retraction_id,version),
 FOREIGN KEY(retraction_id,feedback_id,feedback_version)
  REFERENCES feedback_retraction(retraction_id,feedback_id,feedback_version)
);
