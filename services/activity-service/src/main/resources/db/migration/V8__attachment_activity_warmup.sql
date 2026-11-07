-- Preserve all Inbox rows/checkpoints; re-read original retained attachment events.
UPDATE activity_projection_state SET seeded=false,ready=false WHERE singleton=true;
