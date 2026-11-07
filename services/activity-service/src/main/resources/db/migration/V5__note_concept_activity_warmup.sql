-- Capture retained Note Concept association history without deleting Inbox rows or checkpoints.
UPDATE activity_projection_state SET seeded=false,ready=false WHERE singleton=true;
