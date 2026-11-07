-- A reviewer membership alone never confers expert/official authority.
CREATE TABLE public_knowledge_capability (
 space_id uuid NOT NULL REFERENCES public_knowledge_space(space_id),principal_id uuid NOT NULL,
 capability text NOT NULL CHECK(capability IN ('CLAIM_EXPERT_VERIFY','MAPPING_OFFICIAL_REVIEW')),
 provenance text NOT NULL CHECK(char_length(provenance) BETWEEN 1 AND 2000),
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),updated_at timestamptz NOT NULL,
 PRIMARY KEY(space_id,principal_id,capability)
);
CREATE TABLE public_knowledge_capability_revision (
 revision_id uuid PRIMARY KEY,space_id uuid NOT NULL,principal_id uuid NOT NULL,
 capability text NOT NULL,provenance text NOT NULL,state text NOT NULL,occurred_at timestamptz NOT NULL
);
