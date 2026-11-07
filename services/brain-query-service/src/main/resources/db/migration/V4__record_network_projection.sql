-- Add typed disposable records without rewriting existing projection snapshots/checkpoints.
ALTER TABLE brain_network_snapshot DROP CONSTRAINT brain_network_snapshot_kind_check;
ALTER TABLE brain_network_snapshot ADD CONSTRAINT brain_network_snapshot_kind_check CHECK(kind IN ('NOTE','LINKS','NOTE_CONCEPTS','CONCEPT','CLAIM','BOOKMARK','QUESTION','DECISION','BOOKMARK_CONCEPTS','QUESTION_CONCEPTS','DECISION_CONCEPTS'));
ALTER TABLE brain_network_work DROP CONSTRAINT brain_network_work_kind_check;
ALTER TABLE brain_network_work ADD CONSTRAINT brain_network_work_kind_check CHECK(kind IN ('NOTE','CONCEPT','CLAIM','BOOKMARK','QUESTION','DECISION'));
