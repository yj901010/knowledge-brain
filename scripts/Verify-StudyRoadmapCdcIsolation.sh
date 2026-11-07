#!/usr/bin/env bash
set -euo pipefail
export PGHOST=brain-postgres.brain-data.svc PGPORT=5432 PGPASSWORD="$CDC_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U brain_collaboration_cdc -d brain_collaboration -c 'SELECT 1 FROM public.outbox_event LIMIT 1' >/dev/null
for table in study_roadmap_binding study_roadmap_revision group_study group_study_revision; do
 test "$(psql -X -At -v ON_ERROR_STOP=1 -U brain_collaboration_cdc -d brain_collaboration -c "SELECT to_regclass('public.$table') IS NOT NULL")" = t
 if psql -X -v ON_ERROR_STOP=1 -U brain_collaboration_cdc -d brain_collaboration -c "SELECT * FROM public.$table LIMIT 1" >/dev/null 2>&1; then
  echo 'FAIL Collaboration CDC can read canonical data';exit 1
 fi
done
echo 'PASS Study/current revision and Group/current revision tables deny CDC SELECT; Outbox remains readable'
