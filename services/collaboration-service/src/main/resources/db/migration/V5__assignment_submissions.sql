-- All constraints remain within Collaboration; no external principal/Space FK.
ALTER TABLE study_assignment ADD CONSTRAINT assignment_parent_tuple UNIQUE(assignment_id,study_id,group_id,space_id);
CREATE TABLE assignment_submission (
 submission_id uuid PRIMARY KEY,assignment_id uuid NOT NULL,study_id uuid NOT NULL,group_id uuid NOT NULL,space_id uuid NOT NULL,
 author_id uuid NOT NULL,version bigint NOT NULL CHECK(version>0),snapshot jsonb NOT NULL,
 UNIQUE(assignment_id,author_id),UNIQUE(submission_id,assignment_id),
 FOREIGN KEY(assignment_id,study_id,group_id,space_id) REFERENCES study_assignment(assignment_id,study_id,group_id,space_id)
);
CREATE INDEX assignment_submission_catalog ON assignment_submission(assignment_id,group_id,space_id,study_id,submission_id);
CREATE TABLE assignment_submission_revision (
 revision_id uuid PRIMARY KEY,submission_id uuid NOT NULL,version bigint NOT NULL CHECK(version>0),
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,request_key char(32) NOT NULL CHECK(request_key ~ '^[0-9a-f]{32}$'),
 base_version bigint NOT NULL CHECK(base_version>=0 AND version=base_version+1),assignment_id uuid NOT NULL,assignment_version bigint NOT NULL,
 snapshot jsonb NOT NULL,UNIQUE(submission_id,version),UNIQUE(submission_id,request_key),
 FOREIGN KEY(submission_id,assignment_id) REFERENCES assignment_submission(submission_id,assignment_id),
 FOREIGN KEY(assignment_id,assignment_version) REFERENCES study_assignment_revision(assignment_id,version)
);
