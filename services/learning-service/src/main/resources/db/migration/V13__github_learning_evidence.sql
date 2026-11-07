-- Learning owns all evidence/session relationships; GitHub IDs remain external references.
ALTER TABLE learning_evidence ADD COLUMN github_repository_id uuid;
ALTER TABLE learning_evidence ADD COLUMN github_commit_id uuid;
ALTER TABLE learning_evidence DROP CONSTRAINT learning_evidence_kind_check;
ALTER TABLE learning_evidence ADD CONSTRAINT learning_evidence_kind_check CHECK(kind IN ('NOTE','CONCEPT','MEDIA','GITHUB'));
ALTER TABLE learning_evidence DROP CONSTRAINT learning_evidence_source_shape;
ALTER TABLE learning_evidence ADD CONSTRAINT learning_evidence_source_shape CHECK (
 (kind='NOTE' AND note_id IS NOT NULL AND note_version IS NOT NULL AND note_version>0 AND concept_space_id IS NULL AND concept_id IS NULL AND concept_version IS NULL AND concept_visibility IS NULL AND media_id IS NULL AND media_version IS NULL AND github_repository_id IS NULL AND github_commit_id IS NULL)
 OR (kind='CONCEPT' AND note_id IS NULL AND note_version IS NULL AND media_id IS NULL AND media_version IS NULL AND concept_space_id IS NOT NULL AND concept_id IS NOT NULL AND concept_version IS NOT NULL AND concept_version>0 AND concept_visibility IS NOT NULL AND concept_visibility IN ('PRIVATE','PUBLIC') AND github_repository_id IS NULL AND github_commit_id IS NULL)
 OR (kind='MEDIA' AND note_id IS NULL AND note_version IS NULL AND concept_space_id IS NULL AND concept_id IS NULL AND concept_version IS NULL AND concept_visibility IS NULL AND media_id IS NOT NULL AND media_version IS NOT NULL AND media_version>0 AND github_repository_id IS NULL AND github_commit_id IS NULL)
 OR (kind='GITHUB' AND note_id IS NULL AND note_version IS NULL AND concept_space_id IS NULL AND concept_id IS NULL AND concept_version IS NULL AND concept_visibility IS NULL AND media_id IS NULL AND media_version IS NULL AND github_repository_id IS NOT NULL AND github_commit_id IS NOT NULL)
);
CREATE UNIQUE INDEX learning_evidence_active_github_pin ON learning_evidence(roadmap_id,node_id,github_repository_id,github_commit_id) WHERE kind='GITHUB' AND state='ACTIVE';
CREATE INDEX learning_evidence_github_reverse ON learning_evidence(github_repository_id,github_commit_id,state,evidence_id) WHERE kind='GITHUB';
