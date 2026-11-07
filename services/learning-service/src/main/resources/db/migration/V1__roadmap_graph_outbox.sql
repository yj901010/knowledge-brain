CREATE TABLE roadmap (
 roadmap_id uuid PRIMARY KEY, space_id uuid NOT NULL,
 visibility text NOT NULL CHECK(visibility='PRIVATE'),
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),
 version bigint NOT NULL CHECK(version>0), title text NOT NULL, description text NOT NULL,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE INDEX roadmap_space_state_id ON roadmap(space_id,state,roadmap_id);
CREATE TABLE roadmap_node (
 node_id uuid PRIMARY KEY, roadmap_id uuid NOT NULL REFERENCES roadmap(roadmap_id),
 title text NOT NULL, description text NOT NULL, state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),
 UNIQUE(roadmap_id,node_id)
);
CREATE TABLE roadmap_edge (
 edge_id uuid PRIMARY KEY, roadmap_id uuid NOT NULL REFERENCES roadmap(roadmap_id),
 kind text NOT NULL CHECK(kind IN ('PREREQUISITE_OF','RECOMMENDED_BEFORE','PART_OF','REQUIRED_FOR','OPTIONAL_FOR','RELATED_TO')),
 source_node_id uuid NOT NULL, target_node_id uuid NOT NULL,
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),
 CHECK(source_node_id<>target_node_id),
 FOREIGN KEY(roadmap_id,source_node_id) REFERENCES roadmap_node(roadmap_id,node_id),
 FOREIGN KEY(roadmap_id,target_node_id) REFERENCES roadmap_node(roadmap_id,node_id)
);
CREATE UNIQUE INDEX roadmap_active_edge_semantics ON roadmap_edge(roadmap_id,kind,source_node_id,target_node_id) WHERE state='ACTIVE';
CREATE TABLE roadmap_revision (
 revision_id uuid PRIMARY KEY, roadmap_id uuid NOT NULL REFERENCES roadmap(roadmap_id),
 version bigint NOT NULL CHECK(version>0), actor_id uuid NOT NULL, occurred_at timestamptz NOT NULL,
 snapshot jsonb NOT NULL, UNIQUE(roadmap_id,version)
);
CREATE TABLE outbox_event (
 id uuid PRIMARY KEY, aggregatetype varchar(100) NOT NULL, aggregateid uuid NOT NULL,
 type varchar(100) NOT NULL, occurred_at timestamptz NOT NULL, trace_id varchar(32) NOT NULL,
 schema_group varchar(100) NOT NULL, schema_artifact varchar(100) NOT NULL, schema_version varchar(30) NOT NULL,
 payload bytea NOT NULL
);
