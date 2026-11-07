-- Opaque consumer scope; no cross-service foreign keys or domain authorization.
ALTER TABLE media ADD CONSTRAINT domain_media_scope_tuple UNIQUE(media_id,space_id,actor_id);
CREATE TABLE domain_media_allocation (
 media_id uuid PRIMARY KEY REFERENCES media(media_id), space_id uuid NOT NULL,
 context_id uuid NOT NULL, owner_id uuid NOT NULL, request_key char(32) NOT NULL,
 original jsonb NOT NULL,
 FOREIGN KEY(media_id,space_id,owner_id) REFERENCES media(media_id,space_id,actor_id),
 UNIQUE(space_id,context_id,owner_id,request_key)
);
