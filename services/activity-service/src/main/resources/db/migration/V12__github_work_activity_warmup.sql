-- Retain every Inbox record, offset, source fact and projection row.
UPDATE activity_projection_state SET seeded=false,ready=false WHERE singleton=true;
