-- Same Knowledge owner and same Space; never a cross-service foreign key.
ALTER TABLE concept ADD CONSTRAINT concept_space_identity_key UNIQUE (space_id, concept_id);

CREATE TABLE alias (
  alias_id uuid PRIMARY KEY,
  concept_id uuid NOT NULL,
  space_id uuid NOT NULL,
  visibility text NOT NULL DEFAULT 'PRIVATE' CHECK (visibility = 'PRIVATE'),
  scope text NOT NULL DEFAULT 'SPACE' CHECK (scope = 'SPACE'),
  state text NOT NULL CHECK (state IN ('ACTIVE','RETIRED')),
  version bigint NOT NULL CHECK (version > 0),
  label text NOT NULL CHECK (char_length(label) BETWEEN 1 AND 200 AND btrim(label) <> ''),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  FOREIGN KEY (space_id, concept_id) REFERENCES concept (space_id, concept_id)
);
-- Exact, case-sensitive labels. Retired labels can be reused with a new immutable ID.
CREATE UNIQUE INDEX alias_active_label_key ON alias (space_id, concept_id, label) WHERE state='ACTIVE';
CREATE INDEX alias_concept_active_idx ON alias (space_id, concept_id, alias_id) WHERE state='ACTIVE';

CREATE TABLE alias_revision (
  revision_id uuid PRIMARY KEY,
  alias_id uuid NOT NULL REFERENCES alias(alias_id),
  version bigint NOT NULL CHECK (version > 0),
  state text NOT NULL CHECK (state IN ('ACTIVE','RETIRED')),
  label text NOT NULL CHECK (char_length(label) BETWEEN 1 AND 200 AND btrim(label) <> ''),
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  UNIQUE (alias_id, version)
);
