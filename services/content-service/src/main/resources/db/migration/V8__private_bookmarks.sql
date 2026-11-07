CREATE TABLE bookmark (
 bookmark_id uuid PRIMARY KEY,space_id uuid NOT NULL,
 state text NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),version bigint NOT NULL CHECK(version>0),
 request_key char(32) NOT NULL,request_hash char(64) NOT NULL,snapshot jsonb NOT NULL,
 UNIQUE(space_id,request_key)
);
CREATE INDEX bookmark_catalog ON bookmark(space_id,state,bookmark_id);
CREATE TABLE bookmark_revision (
 revision_id uuid PRIMARY KEY,bookmark_id uuid NOT NULL REFERENCES bookmark(bookmark_id),
 version bigint NOT NULL CHECK(version>0),actor_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,UNIQUE(bookmark_id,version)
);
