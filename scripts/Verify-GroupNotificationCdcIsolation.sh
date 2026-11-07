#!/usr/bin/env bash
set -euo pipefail
export PGHOST=brain-postgres.brain-data.svc PGPORT=5432 PGPASSWORD="$CDC_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U brain_notification_cdc -d brain_notification -c 'SELECT 1 FROM public.outbox_event LIMIT 1' >/dev/null
for table in group_review_preference group_review_preference_revision group_review_receipt group_review_notification group_review_revision group_review_runtime; do
 test "$(psql -X -At -v ON_ERROR_STOP=1 -U brain_notification_cdc -d brain_notification -c "SELECT to_regclass('public.$table') IS NOT NULL")" = t
 if psql -X -v ON_ERROR_STOP=1 -U brain_notification_cdc -d brain_notification -c "SELECT * FROM public.$table LIMIT 1" >/dev/null 2>&1; then echo 'FAIL CDC read canonical Group inbox';exit 1;fi
done
test "$(psql -X -At -v ON_ERROR_STOP=1 -U brain_notification_cdc -d brain_notification -c "SELECT count(*)=1 AND bool_and(tablename='outbox_event') FROM pg_publication_tables WHERE pubname='notification_outbox'")" = t
echo 'PASS six Group canonical tables deny CDC SELECT; Outbox readable; publication remains Outbox only'
