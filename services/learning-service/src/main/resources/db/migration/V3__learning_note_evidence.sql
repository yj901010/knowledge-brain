CREATE TABLE learning_evidence (
 evidence_id uuid PRIMARY KEY,
 roadmap_id uuid NOT NULL REFERENCES roadmap(roadmap_id),
 node_id uuid NOT NULL REFERENCES roadmap_node(node_id),
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),
 version bigint NOT NULL CHECK(version>0),
 note_id uuid NOT NULL,
 note_version bigint NOT NULL CHECK(note_version>0),
 request_key text NOT NULL CHECK(request_key ~ '^[0-9a-f]{32}$'),
 request_fingerprint text NOT NULL,
 UNIQUE(roadmap_id,request_key)
);
-- Note IDs are external references, deliberately no cross-service foreign key.
CREATE UNIQUE INDEX learning_evidence_active_pin ON learning_evidence(roadmap_id,node_id,note_id,note_version) WHERE state='ACTIVE';
CREATE INDEX learning_evidence_catalog ON learning_evidence(roadmap_id,evidence_id) WHERE state='ACTIVE';
CREATE TABLE learning_evidence_revision (
 revision_id uuid PRIMARY KEY,
 evidence_id uuid NOT NULL REFERENCES learning_evidence(evidence_id),
 version bigint NOT NULL CHECK(version>0),
 actor_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL,
 snapshot jsonb NOT NULL,
 UNIQUE(evidence_id,version)
);
