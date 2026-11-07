ALTER TABLE concept DROP CONSTRAINT concept_visibility_check;
ALTER TABLE concept ADD CONSTRAINT concept_visibility_check CHECK (visibility IN ('PRIVATE','PUBLIC'));
CREATE TABLE contribution (
  contribution_id uuid PRIMARY KEY,
  space_id uuid NOT NULL,
  operation text NOT NULL CHECK(operation IN ('CREATE','UPDATE')),
  concept_id uuid NOT NULL,
  base_version bigint NOT NULL CHECK(base_version >= 0),
  title text NOT NULL CHECK(char_length(title) BETWEEN 1 AND 200 AND btrim(title) <> ''),
  description text NOT NULL CHECK(octet_length(description)<=32768),
  rationale text NOT NULL CHECK(char_length(rationale) BETWEEN 1 AND 2000 AND btrim(rationale) <> ''),
  author_id uuid NOT NULL,
  state text NOT NULL CHECK(state IN ('SUBMITTED','APPROVED','REJECTED','MERGED')),
  version bigint NOT NULL CHECK(version > 0),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  merged_concept_version bigint,
  CHECK((operation='CREATE' AND base_version=0) OR (operation='UPDATE' AND base_version>0)),
  CHECK((state='MERGED')=(merged_concept_version IS NOT NULL))
);
CREATE INDEX contribution_space_idx ON contribution(space_id,contribution_id);
CREATE TABLE contribution_review (
  review_id uuid PRIMARY KEY,
  contribution_id uuid NOT NULL REFERENCES contribution(contribution_id),
  contribution_version bigint NOT NULL,
  reviewer_id uuid NOT NULL,
  decision text NOT NULL CHECK(decision IN ('APPROVED','REJECTED','MERGED')),
  reason text NOT NULL CHECK(char_length(reason)<=2000),
  occurred_at timestamptz NOT NULL,
  UNIQUE(contribution_id,contribution_version)
);
