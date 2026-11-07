ALTER TABLE note ADD COLUMN attachment_version bigint NOT NULL DEFAULT 0 CHECK(attachment_version>=0);
CREATE TABLE note_attachment (
 attachment_id uuid PRIMARY KEY,space_id uuid NOT NULL,note_id uuid NOT NULL,media_id uuid NOT NULL,
 ordinal integer NOT NULL CHECK(ordinal>=0),filename text NOT NULL,mime_type text NOT NULL,byte_size bigint NOT NULL,sha256 char(64) NOT NULL,
 FOREIGN KEY(space_id,note_id) REFERENCES note(space_id,note_id),UNIQUE(note_id,media_id),UNIQUE(note_id,ordinal)
);
CREATE TABLE note_attachment_revision (
 revision_id uuid PRIMARY KEY,space_id uuid NOT NULL,note_id uuid NOT NULL,attachment_version bigint NOT NULL CHECK(attachment_version>0),
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,
 FOREIGN KEY(space_id,note_id) REFERENCES note(space_id,note_id),UNIQUE(note_id,attachment_version)
);
