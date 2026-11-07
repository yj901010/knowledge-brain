-- Backfill earlier workbench rows and normalize even an older rolling writer.
-- sha256(bytea) is built into PostgreSQL17; no extension or dependency is needed.
CREATE FUNCTION public_knowledge_identity(k text,c jsonb,r uuid) RETURNS text
LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT encode(sha256(convert_to(CASE k
  WHEN 'ALIAS' THEN '['||to_jsonb(c->>'conceptId')::text||','||to_jsonb(c->>'scope')::text||','||to_jsonb(c->>'scopeValue')::text||','||to_jsonb(c->>'label')::text||']'
  WHEN 'CLAIM' THEN '['||to_jsonb(c->>'subjectId')::text||','||to_jsonb(c->>'predicateId')::text||','||to_jsonb(c->>'objectType')::text||','||to_jsonb(c->>'objectValue')::text||']'
  WHEN 'MAPPING' THEN '['||to_jsonb(c->>'conceptId')::text||','||to_jsonb(c->>'system')::text||','||to_jsonb(c->>'code')::text||','||to_jsonb(c->>'systemVersion')::text||']'
  WHEN 'VERIFICATION' THEN '['||to_jsonb(c->>'claimId')::text||','||(c->>'claimVersion')||']'
  ELSE '['||to_jsonb(r::text)::text||']' END,'UTF8')),'hex');
$$;
CREATE FUNCTION normalize_public_knowledge_identity() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN NEW.identity_key=public_knowledge_identity(NEW.kind,NEW.content,NEW.record_id);RETURN NEW;END;
$$;
CREATE TRIGGER public_knowledge_identity_normalization BEFORE INSERT OR UPDATE OF identity_key,kind,content
ON public_knowledge_record FOR EACH ROW EXECUTE FUNCTION normalize_public_knowledge_identity();
UPDATE public_knowledge_record SET identity_key=public_knowledge_identity(kind,content,record_id);
