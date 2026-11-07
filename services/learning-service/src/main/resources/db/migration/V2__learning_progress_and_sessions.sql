CREATE TABLE node_progress (
 progress_id uuid PRIMARY KEY,
 roadmap_id uuid NOT NULL,
 node_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),
 status text NOT NULL CHECK(status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED')),
 updated_at timestamptz NOT NULL,
 UNIQUE(roadmap_id,node_id),
 FOREIGN KEY(roadmap_id,node_id) REFERENCES roadmap_node(roadmap_id,node_id)
);
CREATE TABLE node_progress_revision (
 revision_id uuid PRIMARY KEY, progress_id uuid NOT NULL REFERENCES node_progress(progress_id),
 version bigint NOT NULL CHECK(version>0), actor_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL, snapshot jsonb NOT NULL,
 UNIQUE(progress_id,version)
);
CREATE TABLE study_session (
 session_id uuid PRIMARY KEY,
 roadmap_id uuid NOT NULL,
 node_id uuid NOT NULL,
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),
 version bigint NOT NULL CHECK(version>0),
 started_at timestamptz NOT NULL, ended_at timestamptz NOT NULL,
 duration_seconds bigint NOT NULL CHECK(duration_seconds BETWEEN 1 AND 86400),
 request_key varchar(32) NOT NULL, request_fingerprint varchar(64) NOT NULL,
 UNIQUE(roadmap_id,request_key),
 CHECK(ended_at>started_at),
 FOREIGN KEY(roadmap_id,node_id) REFERENCES roadmap_node(roadmap_id,node_id)
);
CREATE INDEX study_session_catalog ON study_session(roadmap_id,state,session_id);
CREATE INDEX study_session_node_summary ON study_session(roadmap_id,node_id,state);
CREATE TABLE study_session_revision (
 revision_id uuid PRIMARY KEY, session_id uuid NOT NULL REFERENCES study_session(session_id),
 version bigint NOT NULL CHECK(version>0), actor_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL, snapshot jsonb NOT NULL,
 UNIQUE(session_id,version)
);
