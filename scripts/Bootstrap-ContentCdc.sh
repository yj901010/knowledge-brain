#!/usr/bin/env bash
set -euo pipefail
export PGHOST=brain-postgres.brain-data.svc PGPORT=5432
export PGPASSWORD="$ADMIN_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U postgres -d brain_content <<'SQL'
\getenv cdc_password CDC_PASSWORD
SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='brain_content_cdc') AS exists \gset
\if :exists
ALTER ROLE brain_content_cdc LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT CONNECTION LIMIT 4 PASSWORD :'cdc_password';
\else
CREATE ROLE brain_content_cdc LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT CONNECTION LIMIT 4 PASSWORD :'cdc_password';
\endif
GRANT CONNECT ON DATABASE brain_content TO brain_content_cdc;
SQL
export PGPASSWORD="$OWNER_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U brain_content -d brain_content <<'SQL'
GRANT USAGE ON SCHEMA public TO brain_content_cdc;
GRANT SELECT ON TABLE public.outbox_event TO brain_content_cdc;
DO $$ BEGIN
IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname='content_outbox') THEN
CREATE PUBLICATION content_outbox FOR TABLE public.outbox_event WITH (publish='insert,update,delete');
END IF;
IF (SELECT count(*) FROM pg_publication_tables WHERE pubname='content_outbox') <> 1
OR NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname='content_outbox' AND schemaname='public' AND tablename='outbox_event') THEN
RAISE EXCEPTION 'Existing publication has unexpected tables; refusing CDC bootstrap';
END IF;
END $$;
SQL
psql -X -v ON_ERROR_STOP=1 -U brain_content -d brain_content <<'SQL'
DO $$ BEGIN
IF to_regclass('public.bookmark') IS NULL OR to_regclass('public.bookmark_revision') IS NULL THEN
RAISE EXCEPTION 'Migrate own Bookmark tables before CDC verification';
END IF;
END $$;
SQL
psql -X -v ON_ERROR_STOP=1 -U brain_content -d brain_content <<'SQL'
DO $$ BEGIN
IF to_regclass('public.question') IS NULL OR to_regclass('public.question_revision') IS NULL THEN
RAISE EXCEPTION 'Migrate own Question tables before CDC verification';
END IF;
END $$;
SQL
psql -X -v ON_ERROR_STOP=1 -U brain_content -d brain_content <<'SQL'
DO $$ BEGIN
IF to_regclass('public.decision') IS NULL OR to_regclass('public.decision_revision') IS NULL THEN
RAISE EXCEPTION 'Migrate own Decision tables before CDC verification';
END IF;
END $$;
SQL
psql -X -v ON_ERROR_STOP=1 -U brain_content -d brain_content <<'SQL'
DO $$ BEGIN
IF to_regclass('public.record_concepts') IS NULL OR to_regclass('public.record_concept_link') IS NULL OR to_regclass('public.record_concept_revision') IS NULL THEN
RAISE EXCEPTION 'Migrate own record concept tables before CDC verification';
END IF;
END $$;
SQL
export PGPASSWORD="$CDC_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U brain_content_cdc -d brain_content -c 'SELECT count(*) FROM public.outbox_event' >/dev/null
if psql -X -v ON_ERROR_STOP=1 -U brain_content_cdc -d brain_content -c 'SELECT note_id FROM public.note LIMIT 1' >/dev/null 2>&1; then
 echo 'FAIL CDC can read Content notes';exit 1
fi
for table in bookmark bookmark_revision question question_revision decision decision_revision record_concepts record_concept_link record_concept_revision; do
 if psql -X -v ON_ERROR_STOP=1 -U brain_content_cdc -d brain_content -c "SELECT 1 FROM public.$table LIMIT 1" >/dev/null 2>&1; then
  echo "FAIL CDC can read Content canonical table $table";exit 1
 fi
done
for owner in identity space knowledge learning planning collaboration activity media integration notification brainquery keycloak; do
 if psql -X -v ON_ERROR_STOP=1 -U brain_content_cdc -d "brain_$owner" -c 'SELECT 1' >/dev/null 2>&1; then
  echo "FAIL CDC can connect to brain_$owner";exit 1
 fi
done
echo 'PASS CDC outbox SELECT, Content note SELECT denial, nine Record/Concept canonical SELECT denials, 12 other owner DB connection denials, publication restricted to one outbox'
