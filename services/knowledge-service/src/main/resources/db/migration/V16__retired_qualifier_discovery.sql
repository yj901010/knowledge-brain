CREATE INDEX claim_qualifier_retired_idx
    ON claim_qualifier(space_id,subject_concept_id,claim_id,qualifier_id)
    WHERE state='RETIRED';
