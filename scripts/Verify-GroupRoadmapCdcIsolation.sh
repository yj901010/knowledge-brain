#!/usr/bin/env bash
set -euo pipefail
export PGHOST=brain-postgres.brain-data.svc PGPORT=5432 PGPASSWORD="$CDC_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U brain_learning_cdc -d brain_learning -c 'SELECT 1 FROM public.outbox_event LIMIT 1' >/dev/null
for table in group_roadmap_link group_roadmap_link_revision roadmap node_progress; do
 test "$(psql -X -At -v ON_ERROR_STOP=1 -U brain_learning_cdc -d brain_learning -c "SELECT to_regclass('public.$table') IS NOT NULL")" = t
 if psql -X -v ON_ERROR_STOP=1 -U brain_learning_cdc -d brain_learning -c "SELECT * FROM public.$table LIMIT 1" >/dev/null 2>&1; then
  echo 'FAIL Learning CDC can read canonical data';exit 1
 fi
done
echo 'PASS Group links, link revisions and private learning tables deny CDC SELECT; Outbox remains readable'
