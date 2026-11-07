ALTER TABLE space_membership ADD COLUMN group_version BIGINT NOT NULL DEFAULT 1 CHECK(group_version>0);
CREATE TABLE group_invitation (
 invitation_id UUID PRIMARY KEY,
 group_id UUID NOT NULL REFERENCES group_space_origin(group_id),
 space_id UUID NOT NULL REFERENCES space(space_id),
 creator_principal_id UUID NOT NULL,
 request_key CHAR(32) NOT NULL CHECK(request_key ~ '^[0-9a-f]{32}$'),
 code_hash BYTEA NOT NULL UNIQUE CHECK(octet_length(code_hash)=32),
 role TEXT NOT NULL CHECK(role IN ('MEMBER','VIEWER')),
 state TEXT NOT NULL CHECK(state IN ('PENDING','ACCEPTED','REVOKED')),
 version BIGINT NOT NULL CHECK(version IN (1,2)),
 created_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL CHECK(expires_at>created_at),
 accepted_principal_id UUID,
 UNIQUE(space_id,creator_principal_id,request_key),
 CHECK((state='PENDING' AND version=1 AND accepted_principal_id IS NULL) OR (state='REVOKED' AND version=2 AND accepted_principal_id IS NULL) OR (state='ACCEPTED' AND version=2 AND accepted_principal_id IS NOT NULL))
);
CREATE INDEX group_invitation_page ON group_invitation(space_id,invitation_id);
CREATE TABLE group_invitation_revision (
 invitation_id UUID NOT NULL REFERENCES group_invitation(invitation_id),version BIGINT NOT NULL,
 state TEXT NOT NULL,actor_id UUID NOT NULL,occurred_at TIMESTAMPTZ NOT NULL,
 accepted_principal_id UUID,PRIMARY KEY(invitation_id,version)
);
CREATE TABLE group_membership_revision (
 space_id UUID NOT NULL,principal_id UUID NOT NULL,version BIGINT NOT NULL,
 role TEXT NOT NULL,state TEXT NOT NULL,actor_id UUID NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL,reason TEXT NOT NULL,invitation_id UUID REFERENCES group_invitation(invitation_id),
 PRIMARY KEY(space_id,principal_id,version),
 FOREIGN KEY(space_id,principal_id) REFERENCES space_membership(space_id,principal_id)
);
-- Existing Group membership is the migration baseline, not a new domain transition.
INSERT INTO group_membership_revision(space_id,principal_id,version,role,state,actor_id,occurred_at,reason)
SELECT m.space_id,m.principal_id,m.group_version,m.role,m.state,g.creator_principal_id,s.created_at,'BASELINE'
FROM group_space_origin g JOIN space_membership m ON m.space_id=g.space_id JOIN space s ON s.space_id=g.space_id;
