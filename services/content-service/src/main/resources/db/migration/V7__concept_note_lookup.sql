-- Content-owned reverse lookup; Concept identity is an immutable external reference.
CREATE INDEX note_concept_space_target_note_idx ON note_concept_link(space_id,concept_id,note_id);
