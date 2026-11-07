-- java.sql.Date's hybrid calendar could normalize a valid Gregorian date in
-- 1582-10-05..14. The immutable current revision and event retained the literal
-- user intent. Repair only the owner-local catalog column from that same revision;
-- representation, IDs, versions, historical snapshots and existing Outbox stay intact.
UPDATE goal g
SET target_date=(r.snapshot->>'targetDate')::date
FROM goal_revision r
WHERE r.goal_id=g.goal_id AND r.version=g.version
AND g.target_date IS DISTINCT FROM (r.snapshot->>'targetDate')::date;
