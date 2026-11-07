-- All references are Learning-owned. Provenance remains private to the destination Space.
CREATE TABLE roadmap_adoption (
 adoption_id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 roadmap_id uuid NOT NULL UNIQUE REFERENCES roadmap(roadmap_id),
 source_space_id uuid NOT NULL,
 source_roadmap_id uuid NOT NULL,
 source_version bigint NOT NULL CHECK(source_version>0),
 actor_id uuid NOT NULL,
 created_at timestamptz NOT NULL,
 snapshot jsonb NOT NULL,
 UNIQUE(space_id,source_roadmap_id,source_version),
 FOREIGN KEY(source_space_id,source_roadmap_id) REFERENCES public_roadmap(space_id,roadmap_id),
 FOREIGN KEY(source_roadmap_id,source_version) REFERENCES public_roadmap_revision(roadmap_id,version),
 CHECK(space_id<>source_space_id)
);
