-- Knowledge-local inverse Claim discovery by each independently selected role.
CREATE INDEX claim_object_active_idx ON claim (space_id, object_concept_id, claim_id) WHERE state='ACTIVE';
CREATE INDEX claim_predicate_active_idx ON claim (space_id, predicate_concept_id, claim_id) WHERE state='ACTIVE';
