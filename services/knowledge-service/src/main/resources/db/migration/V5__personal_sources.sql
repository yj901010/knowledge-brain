-- Canonical Knowledge-owned source metadata. Sources do not bind or verify Claims.
CREATE TABLE knowledge_source (
  source_id uuid PRIMARY KEY,
  space_id uuid NOT NULL,
  title text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
  url text CHECK (url IS NULL OR char_length(url) BETWEEN 1 AND 2048),
  citation text NOT NULL CHECK (char_length(citation) <= 4000),
  license_note text NOT NULL CHECK (char_length(license_note) <= 1000),
  visibility text NOT NULL DEFAULT 'PRIVATE' CHECK (visibility = 'PRIVATE'),
  state text NOT NULL CHECK (state IN ('ACTIVE','RETIRED')),
  version bigint NOT NULL CHECK (version > 0),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CHECK (url IS NOT NULL OR char_length(citation) > 0),
  UNIQUE (space_id, source_id)
);
-- Duplicate titles/URLs are valid independent references with stable Source IDs.
CREATE INDEX knowledge_source_space_active_idx
  ON knowledge_source (space_id, source_id) WHERE state='ACTIVE';

-- Immutable snapshots remain after Source retirement. No cascading deletion.
CREATE TABLE source_revision (
  revision_id uuid PRIMARY KEY,
  source_id uuid NOT NULL REFERENCES knowledge_source(source_id),
  title text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
  url text CHECK (url IS NULL OR char_length(url) BETWEEN 1 AND 2048),
  citation text NOT NULL CHECK (char_length(citation) <= 4000),
  license_note text NOT NULL CHECK (char_length(license_note) <= 1000),
  version bigint NOT NULL CHECK (version > 0),
  state text NOT NULL CHECK (state IN ('ACTIVE','RETIRED')),
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  CHECK (url IS NOT NULL OR char_length(citation) > 0),
  UNIQUE (source_id, version)
);
