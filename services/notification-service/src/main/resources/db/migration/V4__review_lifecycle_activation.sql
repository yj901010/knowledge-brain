-- Persist the rollout boundary once. Restart/recovery must not move it forward or
-- replay newly-supported historical annotations into an existing opted-in inbox.
CREATE TABLE group_review_source_activation (
 source varchar(32) PRIMARY KEY CHECK(source IN ('feedback-retraction','grade-retraction')),
 enabled_after bigint NOT NULL CHECK(enabled_after>0)
);
WITH cutoff AS (SELECT (extract(epoch FROM clock_timestamp())*1000)::bigint+1 AS millis)
INSERT INTO group_review_source_activation(source,enabled_after)
SELECT name,millis FROM cutoff CROSS JOIN (VALUES ('feedback-retraction'),('grade-retraction')) AS sources(name);
