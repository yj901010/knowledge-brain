CREATE TABLE decision (
 decision_id uuid PRIMARY KEY,space_id uuid NOT NULL,
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),version bigint NOT NULL CHECK(version>0),
 request_key char(32) NOT NULL,request_hash char(64) NOT NULL,snapshot jsonb NOT NULL,
 status text NOT NULL CHECK(status IN ('DRAFT','DECIDED')),
 UNIQUE(space_id,request_key),
 CHECK(snapshot->>'status'=status),
 CHECK(status<>'DECIDED' OR (length(btrim(snapshot->>'outcome'))>0 AND length(btrim(snapshot->>'rationale'))>0))
);
CREATE INDEX decision_catalog ON decision(space_id,state,decision_id);
CREATE INDEX decision_status_catalog ON decision(space_id,state,status,decision_id);
CREATE TABLE decision_revision (
 revision_id uuid PRIMARY KEY,decision_id uuid NOT NULL REFERENCES decision(decision_id),
 version bigint NOT NULL CHECK(version>0),actor_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,UNIQUE(decision_id,version)
);
