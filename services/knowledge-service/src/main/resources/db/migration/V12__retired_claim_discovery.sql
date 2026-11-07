-- Bounded discovery of retained Knowledge-owned facts; no lifecycle/data changes.
CREATE INDEX claim_subject_retired_idx
  ON claim (space_id, subject_concept_id, claim_id) WHERE state='RETIRED';
