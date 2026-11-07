#!/usr/bin/env bash
set -euo pipefail
export PGHOST=brain-postgres.brain-data.svc PGPORT=5432
export PGPASSWORD="$ADMIN_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U postgres -d brain_notification <<'SQL'
\getenv cdc_password CDC_PASSWORD
SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='brain_notification_cdc') AS exists \gset
\if :exists
ALTER ROLE brain_notification_cdc LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT CONNECTION LIMIT 4 PASSWORD :'cdc_password';
\else
CREATE ROLE brain_notification_cdc LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT CONNECTION LIMIT 4 PASSWORD :'cdc_password';
\endif
GRANT CONNECT ON DATABASE brain_notification TO brain_notification_cdc;
SQL
export PGPASSWORD="$OWNER_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U brain_notification -d brain_notification <<'SQL'
GRANT USAGE ON SCHEMA public TO brain_notification_cdc;
GRANT SELECT ON TABLE public.outbox_event TO brain_notification_cdc;
DO $$ BEGIN
IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname='notification_outbox') THEN
CREATE PUBLICATION notification_outbox FOR TABLE public.outbox_event WITH (publish='insert,update,delete');
END IF;
IF (SELECT count(*) FROM pg_publication_tables WHERE pubname='notification_outbox') <> 1
OR NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname='notification_outbox' AND schemaname='public' AND tablename='outbox_event') THEN
RAISE EXCEPTION 'Existing publication has unexpected tables; refusing CDC bootstrap';
END IF;
END $$;
SQL
export PGPASSWORD="$CDC_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U brain_notification_cdc -d brain_notification -c 'SELECT count(*) FROM public.outbox_event' >/dev/null
for table in notification notification_revision notification_inbox notification_runtime notification_preference notification_preference_revision notification_suppression group_review_preference group_review_preference_revision group_review_receipt group_review_notification group_review_revision group_review_runtime group_review_source_activation; do
 if psql -X -v ON_ERROR_STOP=1 -U brain_notification_cdc -d brain_notification -c "SELECT 1 FROM public.$table LIMIT 1" >/dev/null 2>&1; then
  echo "FAIL CDC can read Notification canonical table $table";exit 1
 fi
done
for owner in identity space knowledge content learning collaboration activity media integration planning brainquery keycloak; do
 if psql -X -v ON_ERROR_STOP=1 -U brain_notification_cdc -d "brain_$owner" -c 'SELECT 1' >/dev/null 2>&1; then
  echo "FAIL CDC can connect to brain_$owner";exit 1
 fi
done
echo 'PASS CDC outbox SELECT, 14 Notification canonical table SELECT denials, 12 other owner DB connection denials, publication restricted to one outbox'
