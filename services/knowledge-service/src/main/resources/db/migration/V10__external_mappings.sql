
ALTER TABLE concept_revision ADD UNIQUE(concept_id,revision_id);
CREATE TABLE external_mapping (
 mapping_id uuid PRIMARY KEY, space_id uuid NOT NULL, concept_id uuid NOT NULL,
 concept_revision_id uuid NOT NULL,
 external_system text NOT NULL CHECK(char_length(external_system) BETWEEN 1 AND 100),
 external_code text NOT NULL CHECK(char_length(external_code) BETWEEN 1 AND 200),
 system_version text NOT NULL CHECK(char_length(system_version)<=100),
 external_label text NOT NULL CHECK(char_length(external_label)<=200),
 source_id uuid NOT NULL, source_revision_id uuid NOT NULL,
 mapping_state text NOT NULL CHECK(mapping_state='CANDIDATE'),
 visibility text NOT NULL CHECK(visibility='PRIVATE'),
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),
 version bigint NOT NULL CHECK(version>0),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 FOREIGN KEY(space_id,concept_id) REFERENCES concept(space_id,concept_id),
 FOREIGN KEY(concept_id,concept_revision_id) REFERENCES concept_revision(concept_id,revision_id),
 FOREIGN KEY(space_id,source_id) REFERENCES knowledge_source(space_id,source_id),
 FOREIGN KEY(source_id,source_revision_id) REFERENCES source_revision(source_id,revision_id)
);
CREATE UNIQUE INDEX external_mapping_active_identifier_key ON external_mapping(space_id,concept_id,external_system,external_code,system_version) WHERE state='ACTIVE';
CREATE INDEX external_mapping_concept_active_idx ON external_mapping(space_id,concept_id,mapping_id) WHERE state='ACTIVE';
CREATE TABLE external_mapping_revision (
 revision_id uuid PRIMARY KEY, mapping_id uuid NOT NULL REFERENCES external_mapping(mapping_id),
 version bigint NOT NULL CHECK(version>0),state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),
 mapping_state text NOT NULL CHECK(mapping_state='CANDIDATE'),
 concept_id uuid NOT NULL, concept_revision_id uuid NOT NULL,
 external_system text NOT NULL, external_code text NOT NULL, system_version text NOT NULL, external_label text NOT NULL,
 source_id uuid NOT NULL, source_revision_id uuid NOT NULL, actor_id uuid NOT NULL, occurred_at timestamptz NOT NULL,
 UNIQUE(mapping_id,version),
 FOREIGN KEY(concept_id,concept_revision_id) REFERENCES concept_revision(concept_id,revision_id),
 FOREIGN KEY(source_id,source_revision_id) REFERENCES source_revision(source_id,revision_id)
);
