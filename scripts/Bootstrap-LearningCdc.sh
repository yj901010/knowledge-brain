#!/usr/bin/env bash
set -euo pipefail
export PGHOST=brain-postgres.brain-data.svc PGPORT=5432
export PGPASSWORD="$ADMIN_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U postgres -d brain_learning <<'SQL'
\getenv cdc_password CDC_PASSWORD
SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='brain_learning_cdc') AS exists \gset
\if :exists
ALTER ROLE brain_learning_cdc LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT CONNECTION LIMIT 4 PASSWORD :'cdc_password';
\else
CREATE ROLE brain_learning_cdc LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT CONNECTION LIMIT 4 PASSWORD :'cdc_password';
\endif
GRANT CONNECT ON DATABASE brain_learning TO brain_learning_cdc;
SQL
export PGPASSWORD="$OWNER_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U brain_learning -d brain_learning <<'SQL'
GRANT USAGE ON SCHEMA public TO brain_learning_cdc;
GRANT SELECT ON TABLE public.outbox_event TO brain_learning_cdc;
DO $$ BEGIN
IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname='learning_outbox') THEN
CREATE PUBLICATION learning_outbox FOR TABLE public.outbox_event WITH (publish='insert,update,delete');
END IF;
IF (SELECT count(*) FROM pg_publication_tables WHERE pubname='learning_outbox') <> 1
OR NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname='learning_outbox' AND schemaname='public' AND tablename='outbox_event') THEN
RAISE EXCEPTION 'Existing publication has unexpected tables; refusing CDC bootstrap';
END IF;
END $$;
SQL
export PGPASSWORD="$CDC_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U brain_learning_cdc -d brain_learning -c 'SELECT count(*) FROM public.outbox_event' >/dev/null
for table in roadmap roadmap_node roadmap_edge roadmap_revision node_progress node_progress_revision study_session study_session_revision learning_evidence learning_evidence_revision evidence_study_session evidence_study_session_revision; do
 if psql -X -v ON_ERROR_STOP=1 -U brain_learning_cdc -d brain_learning -c "SELECT 1 FROM public.$table LIMIT 1" >/dev/null 2>&1; then
  echo "FAIL CDC can read Learning canonical table $table";exit 1
 fi
done
for owner in identity space knowledge content planning collaboration activity media integration notification brainquery keycloak; do
 if psql -X -v ON_ERROR_STOP=1 -U brain_learning_cdc -d "brain_$owner" -c 'SELECT 1' >/dev/null 2>&1; then
  echo "FAIL CDC can connect to brain_$owner";exit 1
 fi
done
echo 'PASS CDC outbox SELECT, twelve Learning canonical table SELECT denials, 12 other owner DB connection denials, publication restricted to one outbox'
