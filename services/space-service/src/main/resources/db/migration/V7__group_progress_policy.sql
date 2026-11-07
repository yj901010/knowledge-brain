CREATE TABLE group_progress_policy (
 policy_id uuid PRIMARY KEY, group_id uuid NOT NULL UNIQUE REFERENCES group_space_origin(group_id),
 space_id uuid NOT NULL UNIQUE REFERENCES space(space_id), version bigint NOT NULL CHECK(version>0),
 mode text NOT NULL CHECK(mode IN ('DISABLED','OWNER_SUMMARY')), snapshot jsonb NOT NULL
);
CREATE TABLE group_progress_policy_revision (
 revision_id uuid PRIMARY KEY, policy_id uuid NOT NULL REFERENCES group_progress_policy(policy_id),
 version bigint NOT NULL CHECK(version>0), actor_id uuid NOT NULL, occurred_at timestamptz NOT NULL,
 snapshot jsonb NOT NULL, UNIQUE(policy_id,version)
);
