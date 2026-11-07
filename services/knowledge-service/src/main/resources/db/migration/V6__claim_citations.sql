-- Same-service constraints bind each citation to its own Claim and immutable snapshots.
ALTER TABLE claim ADD UNIQUE (space_id, subject_concept_id, claim_id);
ALTER TABLE claim_revision ADD UNIQUE (claim_id, revision_id);
ALTER TABLE source_revision ADD UNIQUE (source_id, revision_id);

CREATE TABLE claim_citation (
  citation_id uuid PRIMARY KEY,
  space_id uuid NOT NULL,
  subject_concept_id uuid NOT NULL,
  claim_id uuid NOT NULL,
  source_id uuid NOT NULL,
  claim_revision_id uuid NOT NULL,
  source_revision_id uuid NOT NULL,
  visibility text NOT NULL DEFAULT 'PRIVATE' CHECK (visibility = 'PRIVATE'),
  state text NOT NULL CHECK (state IN ('ACTIVE','RETIRED')),
  version bigint NOT NULL CHECK (version > 0),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  FOREIGN KEY (space_id, subject_concept_id, claim_id)
    REFERENCES claim (space_id, subject_concept_id, claim_id),
  FOREIGN KEY (space_id, source_id) REFERENCES knowledge_source (space_id, source_id),
  FOREIGN KEY (claim_id, claim_revision_id) REFERENCES claim_revision (claim_id, revision_id),
  FOREIGN KEY (source_id, source_revision_id) REFERENCES source_revision (source_id, revision_id)
);
-- Unlinking retains the historical aggregate. Re-linking creates a new UUIDv7.
CREATE UNIQUE INDEX claim_citation_active_source_key
  ON claim_citation (space_id, claim_id, source_id) WHERE state='ACTIVE';
CREATE INDEX claim_citation_claim_active_idx
  ON claim_citation (space_id, claim_id, citation_id) WHERE state='ACTIVE';

-- Citation revisions reference retained immutable Claim/Source revisions without duplicating text.
CREATE TABLE citation_revision (
  revision_id uuid PRIMARY KEY,
  citation_id uuid NOT NULL REFERENCES claim_citation(citation_id),
  version bigint NOT NULL CHECK (version > 0),
  state text NOT NULL CHECK (state IN ('ACTIVE','RETIRED')),
  claim_id uuid NOT NULL,
  source_id uuid NOT NULL,
  claim_revision_id uuid NOT NULL,
  source_revision_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  UNIQUE (citation_id, version),
  FOREIGN KEY (claim_id, claim_revision_id) REFERENCES claim_revision (claim_id, revision_id),
  FOREIGN KEY (source_id, source_revision_id) REFERENCES source_revision (source_id, revision_id)
);
