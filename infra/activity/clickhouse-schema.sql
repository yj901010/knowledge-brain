CREATE DATABASE IF NOT EXISTS brain_activity;
CREATE TABLE IF NOT EXISTS brain_activity.timeline (
 event_id UUID,space_id UUID,occurred_at DateTime64(3,'UTC'),entry String
) ENGINE=ReplacingMergeTree PARTITION BY toYYYYMM(occurred_at) ORDER BY (space_id,occurred_at,event_id);
