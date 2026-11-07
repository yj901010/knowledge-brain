ALTER TABLE public_knowledge_record DROP CONSTRAINT public_knowledge_record_kind_check;
ALTER TABLE public_knowledge_record ADD CHECK(kind IN ('ALIAS','CLAIM','SOURCE','EVIDENCE','MAPPING','VERIFICATION','QUALIFIER'));
ALTER TABLE public_knowledge_proposal DROP CONSTRAINT public_knowledge_proposal_kind_check;
ALTER TABLE public_knowledge_proposal ADD CHECK(kind IN ('ALIAS','CLAIM','SOURCE','EVIDENCE','MAPPING','VERIFICATION','QUALIFIER'));
ALTER TABLE public_knowledge_record ADD COLUMN qualifier_pins jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(qualifier_pins)='object');
ALTER TABLE public_knowledge_revision ADD COLUMN qualifier_pins jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(qualifier_pins)='object');
ALTER TABLE public_knowledge_proposal ADD COLUMN qualifier_pins jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(qualifier_pins)='object');
CREATE INDEX public_qualifier_claim_idx ON public_knowledge_record(space_id,(content->>'claimId'),(content->>'claimVersion')) WHERE kind='QUALIFIER' AND state='ACTIVE';
-- Include typed qualifier identity in the existing fixed-width canonical key.
CREATE OR REPLACE FUNCTION public_knowledge_identity(k text,c jsonb,r uuid) RETURNS text LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT encode(sha256(convert_to(CASE k
  WHEN 'ALIAS' THEN '['||to_jsonb(c->>'conceptId')::text||','||to_jsonb(c->>'scope')::text||','||to_jsonb(c->>'scopeValue')::text||','||to_jsonb(c->>'label')::text||']'
  WHEN 'CLAIM' THEN '['||to_jsonb(c->>'subjectId')::text||','||to_jsonb(c->>'predicateId')::text||','||to_jsonb(c->>'objectType')::text||','||to_jsonb(c->>'objectValue')::text||']'
  WHEN 'MAPPING' THEN '['||to_jsonb(c->>'conceptId')::text||','||to_jsonb(c->>'system')::text||','||to_jsonb(c->>'code')::text||','||to_jsonb(c->>'systemVersion')::text||']'
  WHEN 'VERIFICATION' THEN '['||to_jsonb(c->>'claimId')::text||','||(c->>'claimVersion')||']'
  WHEN 'QUALIFIER' THEN '['||to_jsonb(c->>'claimId')::text||','||(c->>'claimVersion')||','||to_jsonb(c->>'predicateId')::text||','||to_jsonb(c->>'valueType')::text||','||to_jsonb(c->>'value')::text||']'
  ELSE '['||to_jsonb(r::text)::text||']' END,'UTF8')),'hex');
$$;
