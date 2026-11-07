-- Add retained metadata history without changing Inbox rows or Kafka checkpoints.
UPDATE activity_projection_state SET seeded=false,ready=false WHERE singleton=true;
