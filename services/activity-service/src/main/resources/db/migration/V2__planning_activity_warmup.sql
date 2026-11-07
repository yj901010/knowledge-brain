-- Preserve all existing Inbox rows; seed both canonical owners before serving.
UPDATE activity_projection_state SET seeded=false,ready=false WHERE singleton=true;
