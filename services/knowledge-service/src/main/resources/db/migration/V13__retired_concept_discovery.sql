CREATE INDEX concept_space_retired_idx ON concept(space_id, concept_id)
    WHERE visibility = 'PRIVATE' AND state = 'RETIRED';
