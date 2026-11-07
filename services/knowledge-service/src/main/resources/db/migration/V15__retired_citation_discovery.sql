CREATE INDEX claim_citation_claim_retired_idx
    ON claim_citation(space_id, subject_concept_id, claim_id, citation_id)
    WHERE state = 'RETIRED';
