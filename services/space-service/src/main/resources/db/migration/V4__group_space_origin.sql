CREATE TABLE group_space_origin (
 group_id UUID PRIMARY KEY,
 space_id UUID NOT NULL UNIQUE REFERENCES space(space_id),
 creator_principal_id UUID NOT NULL
);
CREATE INDEX group_space_creator ON group_space_origin(creator_principal_id,group_id);
COMMENT ON TABLE group_space_origin IS 'Immutable Collaboration Group reference; no cross-service foreign key';
