-- Preserve all existing Claims and revision IDs; literals carry exact canonical text.
ALTER TABLE claim DROP CONSTRAINT claim_object_type_check;
ALTER TABLE claim ALTER COLUMN object_concept_id DROP NOT NULL;
ALTER TABLE claim ADD COLUMN object_value text;
ALTER TABLE claim ADD CONSTRAINT claim_typed_object_check CHECK (
 (object_type='CONCEPT' AND object_concept_id IS NOT NULL AND object_value IS NULL)
 OR (object_type IN ('STRING','NUMBER','DATE','BOOLEAN') AND object_concept_id IS NULL
     AND object_value IS NOT NULL AND length(object_value) BETWEEN 1 AND 1000));
ALTER TABLE claim_revision DROP CONSTRAINT claim_revision_object_type_check;
ALTER TABLE claim_revision ALTER COLUMN object_concept_id DROP NOT NULL;
ALTER TABLE claim_revision ADD COLUMN object_value text;
ALTER TABLE claim_revision ADD CONSTRAINT claim_revision_typed_object_check CHECK (
 (object_type='CONCEPT' AND object_concept_id IS NOT NULL AND object_value IS NULL)
 OR (object_type IN ('STRING','NUMBER','DATE','BOOLEAN') AND object_concept_id IS NULL
     AND object_value IS NOT NULL AND length(object_value) BETWEEN 1 AND 1000));
-- Exact literal duplicate checks serialize under the existing ordered subject lock.
-- Avoid a wide Unicode value index; keep the existing Concept triple unique index.
CREATE INDEX claim_literal_lookup_idx ON claim(space_id,subject_concept_id,predicate_concept_id,object_type)
 WHERE state='ACTIVE' AND object_type<>'CONCEPT';
