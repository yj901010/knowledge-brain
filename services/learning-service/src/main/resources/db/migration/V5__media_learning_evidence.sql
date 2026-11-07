-- Learning owns the relationship; Media IDs deliberately have no cross-service FK.
ALTER TABLE learning_evidence DROP CONSTRAINT learning_evidence_kind_check;
ALTER TABLE learning_evidence ADD CONSTRAINT learning_evidence_kind_check CHECK(kind IN ('NOTE','CONCEPT','MEDIA'));
ALTER TABLE learning_evidence ADD COLUMN media_id uuid;
ALTER TABLE learning_evidence ADD COLUMN media_version bigint;
ALTER TABLE learning_evidence DROP CONSTRAINT learning_evidence_source_shape;
ALTER TABLE learning_evidence ADD CONSTRAINT learning_evidence_source_shape CHECK (
 (kind='NOTE' AND note_id IS NOT NULL AND note_version IS NOT NULL AND note_version>0
  AND concept_space_id IS NULL AND concept_id IS NULL AND concept_version IS NULL AND concept_visibility IS NULL AND media_id IS NULL AND media_version IS NULL)
 OR (kind='CONCEPT' AND note_id IS NULL AND note_version IS NULL AND media_id IS NULL AND media_version IS NULL
  AND concept_space_id IS NOT NULL AND concept_id IS NOT NULL AND concept_version IS NOT NULL AND concept_version>0
  AND concept_visibility IS NOT NULL AND concept_visibility IN ('PRIVATE','PUBLIC'))
 OR (kind='MEDIA' AND note_id IS NULL AND note_version IS NULL
  AND concept_space_id IS NULL AND concept_id IS NULL AND concept_version IS NULL AND concept_visibility IS NULL
  AND media_id IS NOT NULL AND media_version IS NOT NULL AND media_version>0)
);
CREATE UNIQUE INDEX learning_evidence_active_media_pin ON learning_evidence(roadmap_id,node_id,media_id,media_version)
 WHERE kind='MEDIA' AND state='ACTIVE';
