-- All FKs are local to Learning. Existing evidence/session revisions are immutable.
CREATE TABLE evidence_study_session (
 link_id uuid PRIMARY KEY,
 evidence_id uuid NOT NULL UNIQUE REFERENCES learning_evidence(evidence_id),
 version bigint NOT NULL CHECK(version>0),
 state text NOT NULL CHECK(state IN ('LINKED','UNLINKED')),
 session_id uuid REFERENCES study_session(session_id),
 session_version bigint,
 CHECK((state='LINKED' AND session_id IS NOT NULL AND session_version IS NOT NULL AND session_version>0)
 OR (state='UNLINKED' AND session_id IS NULL AND session_version IS NULL))
);
CREATE TABLE evidence_study_session_revision (
 revision_id uuid PRIMARY KEY,
 link_id uuid NOT NULL REFERENCES evidence_study_session(link_id),
 version bigint NOT NULL CHECK(version>0),actor_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,
 UNIQUE(link_id,version)
);
