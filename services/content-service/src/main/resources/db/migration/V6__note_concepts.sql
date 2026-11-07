ALTER TABLE note ADD COLUMN concept_link_version bigint NOT NULL DEFAULT 0 CHECK(concept_link_version>=0);
CREATE TABLE note_concept_link (
 concept_link_id uuid PRIMARY KEY,space_id uuid NOT NULL,note_id uuid NOT NULL,concept_id uuid NOT NULL,
 ordinal integer NOT NULL CHECK(ordinal>=0),
 FOREIGN KEY(space_id,note_id) REFERENCES note(space_id,note_id),UNIQUE(note_id,concept_id),UNIQUE(note_id,ordinal)
);
CREATE TABLE note_concept_link_revision (
 revision_id uuid PRIMARY KEY,space_id uuid NOT NULL,note_id uuid NOT NULL,concept_link_version bigint NOT NULL CHECK(concept_link_version>0),
 actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,
 FOREIGN KEY(space_id,note_id) REFERENCES note(space_id,note_id),UNIQUE(note_id,concept_link_version)
);
