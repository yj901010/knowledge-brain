-- Re-read original retained Note Link events. Preserve all Inbox rows and Kafka checkpoints.
UPDATE activity_projection_state SET seeded=false,ready=false WHERE singleton=true;
