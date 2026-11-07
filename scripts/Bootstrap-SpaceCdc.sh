#!/usr/bin/env bash
set -euo pipefail
export PGHOST=brain-postgres.brain-data.svc PGPORT=5432
export PGPASSWORD="$ADMIN_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U postgres -d brain_space <<'SQL'
\getenv cdc_password CDC_PASSWORD
SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='brain_space_cdc') AS exists \gset
\if :exists
ALTER ROLE brain_space_cdc LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT CONNECTION LIMIT 4 PASSWORD :'cdc_password';
\else
CREATE ROLE brain_space_cdc LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT CONNECTION LIMIT 4 PASSWORD :'cdc_password';
\endif
GRANT CONNECT ON DATABASE brain_space TO brain_space_cdc;
SQL
export PGPASSWORD="$OWNER_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U brain_space -d brain_space <<'SQL'
GRANT USAGE ON SCHEMA public TO brain_space_cdc;
GRANT SELECT ON TABLE public.outbox_event TO brain_space_cdc;
DO $$ BEGIN
IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname='space_outbox') THEN
CREATE PUBLICATION space_outbox FOR TABLE public.outbox_event WITH (publish='insert,update,delete');
END IF;
IF (SELECT count(*) FROM pg_publication_tables WHERE pubname='space_outbox') <> 1
OR NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname='space_outbox' AND schemaname='public' AND tablename='outbox_event') THEN
RAISE EXCEPTION 'Existing publication has unexpected tables; refusing CDC bootstrap';
END IF;
END $$;
SQL
export PGPASSWORD="$CDC_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U brain_space_cdc -d brain_space -c 'SELECT count(*) FROM public.outbox_event' >/dev/null
if psql -X -v ON_ERROR_STOP=1 -U brain_space_cdc -d brain_space -c 'SELECT principal_id FROM public.space_membership LIMIT 1' >/dev/null 2>&1; then
 echo 'FAIL CDC can read Space memberships';exit 1
fi
for owner in identity knowledge content learning planning collaboration activity media integration notification brainquery keycloak; do
 if psql -X -v ON_ERROR_STOP=1 -U brain_space_cdc -d "brain_$owner" -c 'SELECT 1' >/dev/null 2>&1; then
  echo "FAIL CDC can connect to brain_$owner";exit 1
 fi
done
echo 'PASS CDC outbox SELECT, Space membership denial, 12 other owner DB connection denials, publication restricted to one outbox'
