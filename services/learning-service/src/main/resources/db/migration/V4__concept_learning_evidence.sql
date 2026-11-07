ALTER TABLE learning_evidence ADD COLUMN kind text NOT NULL DEFAULT 'NOTE' CHECK(kind IN ('NOTE','CONCEPT'));
ALTER TABLE learning_evidence ADD COLUMN concept_space_id uuid;
ALTER TABLE learning_evidence ADD COLUMN concept_id uuid;
ALTER TABLE learning_evidence ADD COLUMN concept_version bigint;
ALTER TABLE learning_evidence ADD COLUMN concept_visibility text;
ALTER TABLE learning_evidence ALTER COLUMN note_id DROP NOT NULL;
ALTER TABLE learning_evidence ALTER COLUMN note_version DROP NOT NULL;
ALTER TABLE learning_evidence ADD CONSTRAINT learning_evidence_source_shape CHECK (
 (kind='NOTE' AND note_id IS NOT NULL AND note_version IS NOT NULL AND note_version>0
  AND concept_space_id IS NULL AND concept_id IS NULL AND concept_version IS NULL AND concept_visibility IS NULL)
 OR (kind='CONCEPT' AND note_id IS NULL AND note_version IS NULL
  AND concept_space_id IS NOT NULL AND concept_id IS NOT NULL AND concept_version IS NOT NULL AND concept_version>0
  AND concept_visibility IS NOT NULL AND concept_visibility IN ('PRIVATE','PUBLIC'))
);
-- External source IDs have no cross-service FK. Existing Note rows/revisions are unchanged.
CREATE UNIQUE INDEX learning_evidence_active_concept_pin ON learning_evidence(roadmap_id,node_id,concept_space_id,concept_id,concept_version)
 WHERE kind='CONCEPT' AND state='ACTIVE';
