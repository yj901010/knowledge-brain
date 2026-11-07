CREATE TABLE public_roadmap (
 roadmap_id uuid PRIMARY KEY, space_id uuid NOT NULL, version bigint NOT NULL CHECK(version>0),
 title text NOT NULL, updated_at timestamptz NOT NULL, snapshot jsonb NOT NULL
);
CREATE INDEX public_roadmap_catalog ON public_roadmap(space_id,roadmap_id);
CREATE TABLE public_roadmap_revision (
 revision_id uuid PRIMARY KEY, roadmap_id uuid NOT NULL REFERENCES public_roadmap(roadmap_id),
 version bigint NOT NULL CHECK(version>0), actor_id uuid NOT NULL, occurred_at timestamptz NOT NULL,
 snapshot jsonb NOT NULL, UNIQUE(roadmap_id,version)
);
CREATE TABLE roadmap_contribution (
 contribution_id uuid PRIMARY KEY, space_id uuid NOT NULL, roadmap_id uuid NOT NULL,
 base_version bigint NOT NULL CHECK(base_version>=0), author_id uuid NOT NULL,
 state text NOT NULL CHECK(state IN ('SUBMITTED','APPROVED','REJECTED','MERGED')),
 version bigint NOT NULL CHECK(version>0), title text NOT NULL, description text NOT NULL,
 rationale text NOT NULL, graph jsonb NOT NULL, created_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL, merged_roadmap_version bigint
);
CREATE INDEX roadmap_contribution_catalog ON roadmap_contribution(space_id,contribution_id);
CREATE TABLE roadmap_contribution_review (
 review_id uuid PRIMARY KEY, contribution_id uuid NOT NULL REFERENCES roadmap_contribution(contribution_id),
 contribution_version bigint NOT NULL, reviewer_id uuid NOT NULL,
 decision text NOT NULL CHECK(decision IN ('APPROVED','REJECTED','MERGED')),
 reason text NOT NULL, occurred_at timestamptz NOT NULL, UNIQUE(contribution_id,contribution_version)
);
