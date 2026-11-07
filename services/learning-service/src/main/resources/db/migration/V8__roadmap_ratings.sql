CREATE UNIQUE INDEX public_roadmap_scope ON public_roadmap(space_id,roadmap_id);
CREATE UNIQUE INDEX public_roadmap_creator ON roadmap_contribution(roadmap_id)
 WHERE base_version=0 AND state='MERGED';
CREATE TABLE roadmap_rating (
 rating_id uuid PRIMARY KEY, space_id uuid NOT NULL, roadmap_id uuid NOT NULL,
 principal_id uuid NOT NULL, roadmap_version bigint NOT NULL CHECK(roadmap_version>0),
 score smallint NOT NULL CHECK(score BETWEEN 1 AND 5),
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),
 version bigint NOT NULL CHECK(version>0), updated_at timestamptz NOT NULL,
 UNIQUE(roadmap_id,principal_id),
 FOREIGN KEY(space_id,roadmap_id) REFERENCES public_roadmap(space_id,roadmap_id)
);
CREATE INDEX roadmap_rating_current ON roadmap_rating(space_id,roadmap_id,roadmap_version) WHERE state='ACTIVE';
CREATE TABLE roadmap_rating_revision (
 revision_id uuid PRIMARY KEY, rating_id uuid NOT NULL REFERENCES roadmap_rating(rating_id),
 version bigint NOT NULL CHECK(version>0), actor_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL, snapshot jsonb NOT NULL, UNIQUE(rating_id,version)
);
