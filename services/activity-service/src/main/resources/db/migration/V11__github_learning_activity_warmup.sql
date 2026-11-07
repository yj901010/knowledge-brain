-- Preserve Inbox, offsets and facts; seed the two new Learning streams through owner APIs.
UPDATE activity_projection_state SET seeded=false,ready=false WHERE singleton=true;
