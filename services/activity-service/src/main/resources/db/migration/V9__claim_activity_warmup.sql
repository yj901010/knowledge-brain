-- Recover retained Claim V1/V2 history without resetting existing Inbox or offsets.
UPDATE activity_projection_state SET seeded=false,ready=false WHERE singleton=true;
