-- Keep immutable retired Notes discoverable with the existing Space/UUID keyset.
CREATE INDEX note_space_retired_idx ON note(space_id,note_id)
  WHERE visibility='PRIVATE' AND state='RETIRED';
