CREATE INDEX knowledge_source_space_retired_idx ON knowledge_source(space_id, source_id)
    WHERE state = 'RETIRED';
