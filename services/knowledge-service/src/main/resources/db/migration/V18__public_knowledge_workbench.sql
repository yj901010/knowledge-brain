-- Public canonical facts are distinct from PRIVATE working material. No private
-- row/reference is copied: only explicit, reviewed immutable public snapshots.
CREATE TABLE public_knowledge_record (
 record_id uuid PRIMARY KEY, space_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN ('ALIAS','CLAIM','SOURCE','EVIDENCE','MAPPING','VERIFICATION')),
 identity_key text NOT NULL, title text NOT NULL CHECK(char_length(title) BETWEEN 1 AND 200),
 content jsonb NOT NULL CHECK(jsonb_typeof(content)='object'),
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),
 version bigint NOT NULL CHECK(version>0), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 UNIQUE(space_id,record_id)
);
CREATE UNIQUE INDEX public_knowledge_identity_idx ON public_knowledge_record(space_id,kind,identity_key) WHERE state='ACTIVE';
CREATE INDEX public_knowledge_catalog_idx ON public_knowledge_record(space_id,kind,record_id);
CREATE TABLE public_knowledge_revision (
 revision_id uuid PRIMARY KEY, record_id uuid NOT NULL REFERENCES public_knowledge_record(record_id),
 version bigint NOT NULL, title text NOT NULL, content jsonb NOT NULL, state text NOT NULL,
 actor_id uuid NOT NULL, occurred_at timestamptz NOT NULL, UNIQUE(record_id,version)
);
CREATE TABLE public_knowledge_proposal (
 proposal_id uuid PRIMARY KEY,space_id uuid NOT NULL,record_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN ('ALIAS','CLAIM','SOURCE','EVIDENCE','MAPPING','VERIFICATION')),
 operation text NOT NULL CHECK(operation IN ('CREATE','UPDATE','RETIRE')),
 base_version bigint NOT NULL CHECK(base_version>=0),title text NOT NULL,content jsonb NOT NULL,
 rationale text NOT NULL CHECK(char_length(rationale) BETWEEN 1 AND 2000),author_id uuid NOT NULL,
 state text NOT NULL CHECK(state IN ('SUBMITTED','APPROVED','REJECTED','MERGED')),
 version bigint NOT NULL CHECK(version>0),created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,
 merged_version bigint,
 CHECK((operation='CREATE' AND base_version=0) OR (operation<>'CREATE' AND base_version>0)),
 CHECK((state='MERGED')=(merged_version IS NOT NULL))
);
CREATE INDEX public_knowledge_queue_idx ON public_knowledge_proposal(space_id,state,proposal_id);
CREATE TABLE public_knowledge_review (
 review_id uuid PRIMARY KEY, proposal_id uuid NOT NULL REFERENCES public_knowledge_proposal(proposal_id),
 version bigint NOT NULL,reviewer_id uuid NOT NULL,decision text NOT NULL,reason text NOT NULL,
 occurred_at timestamptz NOT NULL,UNIQUE(proposal_id,version)
);
