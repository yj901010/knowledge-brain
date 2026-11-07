-- Keep the initial OWNER audit invariant during rolling deployment of pre-V5 writers.
CREATE FUNCTION group_owner_history_on_insert() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO group_membership_revision(space_id,principal_id,version,role,state,actor_id,occurred_at,reason)
 SELECT NEW.space_id,NEW.principal_id,NEW.group_version,NEW.role,NEW.state,NEW.principal_id,s.created_at,'OWNER_CREATED'
 FROM space s WHERE s.space_id=NEW.space_id AND s.kind='GROUP' AND s.owner_principal_id=NEW.principal_id
 ON CONFLICT DO NOTHING;
 RETURN NEW;
END $$;
CREATE TRIGGER group_owner_history_insert AFTER INSERT ON space_membership FOR EACH ROW WHEN(NEW.role='OWNER') EXECUTE FUNCTION group_owner_history_on_insert();
INSERT INTO group_membership_revision(space_id,principal_id,version,role,state,actor_id,occurred_at,reason)
SELECT m.space_id,m.principal_id,m.group_version,m.role,m.state,g.creator_principal_id,s.created_at,'BASELINE'
FROM group_space_origin g JOIN space_membership m ON m.space_id=g.space_id JOIN space s ON s.space_id=g.space_id WHERE m.role='OWNER'
ON CONFLICT DO NOTHING;
