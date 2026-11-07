-- Backfill retained GitHub commit events; preserve all Inbox entries, source data and offsets.
UPDATE activity_projection_state SET seeded=false,ready=false WHERE singleton=true;
