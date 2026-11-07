-- Only Collaboration-owned parent and immutable attempt references.
CREATE TABLE resubmission_request (
 request_id uuid PRIMARY KEY,submission_id uuid NOT NULL UNIQUE,assignment_id uuid NOT NULL,
 study_id uuid NOT NULL,group_id uuid NOT NULL,space_id uuid NOT NULL,author_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),submission_version bigint NOT NULL CHECK(submission_version>0),
 resolved_submission_version bigint,snapshot jsonb NOT NULL,
 UNIQUE(request_id,submission_id),
 CHECK(resolved_submission_version IS NULL OR resolved_submission_version>submission_version),
 FOREIGN KEY(submission_id,assignment_id,study_id,group_id,space_id,author_id)
  REFERENCES assignment_submission(submission_id,assignment_id,study_id,group_id,space_id,author_id),
 FOREIGN KEY(submission_id,submission_version) REFERENCES assignment_submission_revision(submission_id,version),
 FOREIGN KEY(submission_id,resolved_submission_version) REFERENCES assignment_submission_revision(submission_id,version)
);
CREATE TABLE resubmission_request_revision (
 revision_id uuid PRIMARY KEY,request_id uuid NOT NULL,version bigint NOT NULL CHECK(version>0),
 submission_id uuid NOT NULL,submission_version bigint NOT NULL,resolved_submission_version bigint,
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,
 UNIQUE(request_id,version),
 CHECK(resolved_submission_version IS NULL OR resolved_submission_version>submission_version),
 FOREIGN KEY(request_id,submission_id) REFERENCES resubmission_request(request_id,submission_id),
 FOREIGN KEY(submission_id,submission_version) REFERENCES assignment_submission_revision(submission_id,version),
 FOREIGN KEY(submission_id,resolved_submission_version) REFERENCES assignment_submission_revision(submission_id,version)
);
