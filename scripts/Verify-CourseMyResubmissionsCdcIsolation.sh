#!/usr/bin/env bash
set -euo pipefail
export PGHOST=brain-postgres.brain-data.svc PGPORT=5432 PGPASSWORD="$CDC_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -U brain_collaboration_cdc -d brain_collaboration -c 'SELECT 1 FROM public.outbox_event LIMIT 1' >/dev/null
for table in group_course group_course_revision course_study group_study group_study_revision study_assignment study_assignment_revision assignment_submission assignment_submission_revision submission_grade submission_grade_revision grade_retraction grade_retraction_revision resubmission_request resubmission_request_revision; do
 test "$(psql -X -At -v ON_ERROR_STOP=1 -U brain_collaboration_cdc -d brain_collaboration -c "SELECT to_regclass('public.$table') IS NOT NULL")" = t
 if psql -X -v ON_ERROR_STOP=1 -U brain_collaboration_cdc -d brain_collaboration -c "SELECT * FROM public.$table LIMIT 1" >/dev/null 2>&1; then echo 'FAIL canonical assignment data exposed to CDC';exit 1;fi
done
test "$(psql -X -At -v ON_ERROR_STOP=1 -U brain_collaboration_cdc -d brain_collaboration -c "SELECT count(*)=1 AND bool_and(tablename='outbox_event') FROM pg_publication_tables WHERE pubname='collaboration_outbox'")" = t
echo 'PASS fifteen Course/Study/Assignment/Submission/Grade/Request canonical tables deny CDC SELECT; Outbox readable; publication remains Outbox only'
