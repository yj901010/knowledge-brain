CREATE INDEX alias_retired_idx ON alias(space_id,concept_id,alias_id) WHERE state='RETIRED';
CREATE INDEX external_mapping_retired_idx ON external_mapping(space_id,concept_id,mapping_id) WHERE state='RETIRED';
