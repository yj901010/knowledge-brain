CREATE TABLE record_concepts (
 association_id uuid PRIMARY KEY,space_id uuid NOT NULL,record_type text NOT NULL CHECK(record_type IN ('BOOKMARK','QUESTION','DECISION')),
 record_id uuid NOT NULL,version bigint NOT NULL CHECK(version>0),snapshot jsonb NOT NULL,UNIQUE(space_id,record_type,record_id)
);
CREATE TABLE record_concept_link (
 link_id uuid PRIMARY KEY,association_id uuid NOT NULL REFERENCES record_concepts(association_id),concept_id uuid NOT NULL,
 ordinal integer NOT NULL CHECK(ordinal>=0 AND ordinal<20),UNIQUE(association_id,concept_id),UNIQUE(association_id,ordinal)
);
CREATE INDEX record_concept_reverse ON record_concept_link(concept_id,association_id);
CREATE TABLE record_concept_revision (
 revision_id uuid PRIMARY KEY,association_id uuid NOT NULL REFERENCES record_concepts(association_id),version bigint NOT NULL CHECK(version>0),
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,record_version bigint NOT NULL CHECK(record_version>0),snapshot jsonb NOT NULL,UNIQUE(association_id,version)
);
