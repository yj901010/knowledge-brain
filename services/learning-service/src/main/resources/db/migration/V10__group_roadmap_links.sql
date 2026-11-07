-- space_id and actor_id are immutable external IDs; all FKs stay inside Learning.
CREATE TABLE group_roadmap_link (
 link_id uuid PRIMARY KEY, space_id uuid NOT NULL,
 source_space_id uuid NOT NULL, source_roadmap_id uuid NOT NULL,
 source_version bigint NOT NULL CHECK(source_version>0),
 version bigint NOT NULL CHECK(version>0), state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),
 title text NOT NULL, snapshot jsonb NOT NULL,
 UNIQUE(space_id,source_space_id,source_roadmap_id,source_version),
 FOREIGN KEY(source_space_id,source_roadmap_id) REFERENCES public_roadmap(space_id,roadmap_id),
 FOREIGN KEY(source_roadmap_id,source_version) REFERENCES public_roadmap_revision(roadmap_id,version),
 CHECK(space_id<>source_space_id)
);
CREATE INDEX group_roadmap_catalog ON group_roadmap_link(space_id,link_id);
CREATE TABLE group_roadmap_link_revision (
 revision_id uuid PRIMARY KEY, link_id uuid NOT NULL REFERENCES group_roadmap_link(link_id),
 version bigint NOT NULL CHECK(version>0), actor_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL, snapshot jsonb NOT NULL, UNIQUE(link_id,version)
);
