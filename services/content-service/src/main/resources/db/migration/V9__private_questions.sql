CREATE TABLE question (
 question_id uuid PRIMARY KEY,space_id uuid NOT NULL,
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),version bigint NOT NULL CHECK(version>0),
 request_key char(32) NOT NULL,request_hash char(64) NOT NULL,snapshot jsonb NOT NULL,
 status text NOT NULL CHECK(status IN ('OPEN','ANSWERED')),
 UNIQUE(space_id,request_key),
 CHECK(snapshot->>'status'=status),
 CHECK(status<>'ANSWERED' OR length(btrim(snapshot->>'answer'))>0)
);
CREATE INDEX question_catalog ON question(space_id,state,question_id);
CREATE INDEX question_status_catalog ON question(space_id,state,status,question_id);
CREATE TABLE question_revision (
 revision_id uuid PRIMARY KEY,question_id uuid NOT NULL REFERENCES question(question_id),
 version bigint NOT NULL CHECK(version>0),actor_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,UNIQUE(question_id,version)
);
