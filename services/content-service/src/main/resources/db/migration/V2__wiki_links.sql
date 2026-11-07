ALTER TABLE note ADD COLUMN title_key text;
ALTER TABLE note ADD COLUMN link_version bigint NOT NULL DEFAULT 0 CHECK(link_version>=0);
ALTER TABLE note ADD COLUMN links_dirty boolean NOT NULL DEFAULT true;
ALTER TABLE note ADD CONSTRAINT note_space_id_unique UNIQUE(space_id,note_id);
CREATE INDEX note_title_lookup ON note(space_id,title_key) WHERE state='ACTIVE';
CREATE INDEX note_links_dirty ON note(space_id,note_id) WHERE links_dirty;
CREATE FUNCTION mark_note_links_dirty() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.links_dirty := true;RETURN NEW;END $$;
CREATE TRIGGER note_links_dirty BEFORE UPDATE OF title,markdown,state ON note FOR EACH ROW EXECUTE FUNCTION mark_note_links_dirty();
CREATE TABLE note_link (
 link_id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 source_note_id uuid NOT NULL,
 ordinal integer NOT NULL CHECK(ordinal>=0),
 start_offset integer NOT NULL CHECK(start_offset>=0),
 end_offset integer NOT NULL CHECK(end_offset>start_offset),
 target_token text NOT NULL,
 target_key text NOT NULL,
 requested_target_id uuid,
 label text,
 fragment text,
 target_note_id uuid,
 status text NOT NULL CHECK(status IN ('UNRESOLVED','AMBIGUOUS','RESOLVED','TARGET_RETIRED')),
 FOREIGN KEY(space_id,source_note_id) REFERENCES note(space_id,note_id),
 FOREIGN KEY(space_id,target_note_id) REFERENCES note(space_id,note_id),
 UNIQUE(source_note_id,ordinal),
 CHECK((target_note_id IS NULL AND status IN ('UNRESOLVED','AMBIGUOUS')) OR (target_note_id IS NOT NULL AND status IN ('RESOLVED','TARGET_RETIRED')))
);
CREATE INDEX note_link_backlinks ON note_link(space_id,target_note_id,source_note_id);
CREATE INDEX note_link_unresolved ON note_link(space_id,target_key) WHERE target_note_id IS NULL;
CREATE TABLE note_link_revision (
 revision_id uuid PRIMARY KEY,
 space_id uuid NOT NULL,
 source_note_id uuid NOT NULL,
 link_version bigint NOT NULL CHECK(link_version>0),
 note_version bigint NOT NULL CHECK(note_version>0),
 actor_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL,
 payload bytea NOT NULL,
 FOREIGN KEY(space_id,source_note_id) REFERENCES note(space_id,note_id),
 UNIQUE(source_note_id,link_version)
);
