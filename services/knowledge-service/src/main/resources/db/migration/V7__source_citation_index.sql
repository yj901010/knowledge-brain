-- Knowledge-local discovery of active citations by their retained Source identity.
CREATE INDEX claim_citation_source_active_idx
  ON claim_citation (space_id, source_id, citation_id) WHERE state='ACTIVE';
