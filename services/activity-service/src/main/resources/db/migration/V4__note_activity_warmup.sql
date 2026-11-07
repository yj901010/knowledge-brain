-- Add Note and Daily Note history without changing existing Inbox rows or checkpoints.
UPDATE activity_projection_state SET seeded=false,ready=false WHERE singleton=true;
