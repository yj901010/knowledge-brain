
-- Independent private conditions pinned to immutable Claim revisions.
CREATE TABLE claim_qualifier (
 qualifier_id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 subject_concept_id uuid NOT NULL,
 claim_id uuid NOT NULL REFERENCES claim(claim_id),
 claim_revision_id uuid NOT NULL REFERENCES claim_revision(revision_id),
 predicate_concept_id uuid NOT NULL,
 value_type text NOT NULL CHECK (value_type IN ('CONCEPT','STRING','NUMBER','DATE','BOOLEAN')),
 value_text text NOT NULL CHECK (length(value_text) BETWEEN 1 AND 1000),
 visibility text NOT NULL CHECK (visibility='PRIVATE'),
 state text NOT NULL CHECK (state IN ('ACTIVE','RETIRED')),
 version bigint NOT NULL CHECK (version>0),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 FOREIGN KEY(space_id,subject_concept_id) REFERENCES concept(space_id,concept_id),
 FOREIGN KEY(space_id,predicate_concept_id) REFERENCES concept(space_id,concept_id)
);
-- Claim row locking serializes duplicate checks, avoiding a wide UTF-8 value index.
CREATE INDEX claim_qualifier_active_idx ON claim_qualifier(space_id,subject_concept_id,claim_id,qualifier_id) WHERE state='ACTIVE';
CREATE TABLE qualifier_revision (
 revision_id uuid PRIMARY KEY,
 qualifier_id uuid NOT NULL REFERENCES claim_qualifier(qualifier_id),
 version bigint NOT NULL CHECK(version>0),
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),
 claim_id uuid NOT NULL REFERENCES claim(claim_id),
 claim_revision_id uuid NOT NULL REFERENCES claim_revision(revision_id),
 predicate_concept_id uuid NOT NULL,
 value_type text NOT NULL CHECK(value_type IN ('CONCEPT','STRING','NUMBER','DATE','BOOLEAN')),
 value_text text NOT NULL,
 actor_id uuid NOT NULL, occurred_at timestamptz NOT NULL,
 UNIQUE(qualifier_id,version)
);
