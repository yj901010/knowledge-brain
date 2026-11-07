-- All FKs remain in Collaboration; external principal/Space IDs are immutable references.
CREATE TABLE study_discussion (
 discussion_id uuid PRIMARY KEY,study_id uuid NOT NULL,group_id uuid NOT NULL,space_id uuid NOT NULL,creator_id uuid NOT NULL,
 request_key char(32) NOT NULL CHECK(request_key ~ '^[0-9a-f]{32}$'),creation_title text NOT NULL,creation_description text NOT NULL,
 version bigint NOT NULL CHECK(version>0),title text NOT NULL CHECK(char_length(title) BETWEEN 1 AND 200),
 state text NOT NULL CHECK(state IN ('OPEN','LOCKED','RETIRED')),snapshot jsonb NOT NULL,
 UNIQUE(study_id,creator_id,request_key),UNIQUE(discussion_id,study_id,group_id,space_id),
 FOREIGN KEY(study_id,group_id,space_id) REFERENCES group_study(study_id,group_id,space_id)
);
CREATE INDEX study_discussion_catalog ON study_discussion(study_id,group_id,space_id,discussion_id);
CREATE TABLE study_discussion_revision (
 revision_id uuid PRIMARY KEY,discussion_id uuid NOT NULL REFERENCES study_discussion(discussion_id),
 version bigint NOT NULL CHECK(version>0),actor_id uuid NOT NULL,occurred_at timestamptz NOT NULL,snapshot jsonb NOT NULL,
 UNIQUE(discussion_id,version)
);
CREATE TABLE discussion_reply (
 reply_id uuid PRIMARY KEY,discussion_id uuid NOT NULL,study_id uuid NOT NULL,group_id uuid NOT NULL,space_id uuid NOT NULL,author_id uuid NOT NULL,
 request_key char(32) NOT NULL CHECK(request_key ~ '^[0-9a-f]{32}$'),discussion_version bigint NOT NULL CHECK(discussion_version>0),snapshot jsonb NOT NULL,
 UNIQUE(discussion_id,author_id,request_key),
 FOREIGN KEY(discussion_id,study_id,group_id,space_id) REFERENCES study_discussion(discussion_id,study_id,group_id,space_id),
 FOREIGN KEY(discussion_id,discussion_version) REFERENCES study_discussion_revision(discussion_id,version)
);
CREATE INDEX discussion_reply_catalog ON discussion_reply(discussion_id,reply_id);
