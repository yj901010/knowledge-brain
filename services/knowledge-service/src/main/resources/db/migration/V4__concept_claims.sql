-- Canonical Knowledge-owned triples. Same-Space foreign keys are within this service.
CREATE TABLE claim (
  claim_id uuid PRIMARY KEY,
  space_id uuid NOT NULL,
  subject_concept_id uuid NOT NULL,
  predicate_concept_id uuid NOT NULL,
  object_concept_id uuid NOT NULL,
  object_type text NOT NULL CHECK (object_type = 'CONCEPT'),
  visibility text NOT NULL DEFAULT 'PRIVATE' CHECK (visibility = 'PRIVATE'),
  state text NOT NULL CHECK (state IN ('ACTIVE','RETIRED')),
  version bigint NOT NULL CHECK (version > 0),
  verification_state text NOT NULL DEFAULT 'DRAFT' CHECK (verification_state = 'DRAFT'),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  FOREIGN KEY (space_id, subject_concept_id) REFERENCES concept (space_id, concept_id),
  FOREIGN KEY (space_id, predicate_concept_id) REFERENCES concept (space_id, concept_id),
  FOREIGN KEY (space_id, object_concept_id) REFERENCES concept (space_id, concept_id)
);
-- Retired triples retain immutable identities; the same triple may be recreated.
CREATE UNIQUE INDEX claim_active_triple_key
  ON claim (space_id, subject_concept_id, predicate_concept_id, object_concept_id)
  WHERE state='ACTIVE';
CREATE INDEX claim_subject_active_idx
  ON claim (space_id, subject_concept_id, claim_id) WHERE state='ACTIVE';

-- Snapshots are retained when any referenced Concept retires. No cascading deletion.
CREATE TABLE claim_revision (
  revision_id uuid PRIMARY KEY,
  claim_id uuid NOT NULL REFERENCES claim(claim_id),
  subject_concept_id uuid NOT NULL,
  predicate_concept_id uuid NOT NULL,
  object_concept_id uuid NOT NULL,
  object_type text NOT NULL CHECK (object_type = 'CONCEPT'),
  version bigint NOT NULL CHECK (version > 0),
  state text NOT NULL CHECK (state IN ('ACTIVE','RETIRED')),
  verification_state text NOT NULL CHECK (verification_state = 'DRAFT'),
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  UNIQUE (claim_id, version)
);
