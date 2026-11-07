ALTER TABLE group_study ADD CONSTRAINT study_scope_pair UNIQUE(study_id,group_id,space_id);
CREATE TABLE study_roadmap_binding (
 binding_id uuid PRIMARY KEY, study_id uuid NOT NULL UNIQUE, group_id uuid NOT NULL, space_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0), snapshot jsonb NOT NULL,
 FOREIGN KEY(study_id,group_id,space_id) REFERENCES group_study(study_id,group_id,space_id)
 -- Learning IDs in snapshot are external immutable references, never foreign keys.
);
CREATE TABLE study_roadmap_revision (
 revision_id uuid PRIMARY KEY, binding_id uuid NOT NULL REFERENCES study_roadmap_binding(binding_id),
 version bigint NOT NULL CHECK(version>0), actor_id uuid NOT NULL, occurred_at timestamptz NOT NULL,
 snapshot jsonb NOT NULL, UNIQUE(binding_id,version)
);
