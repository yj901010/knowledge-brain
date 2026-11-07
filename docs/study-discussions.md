# Study discussions

Selected Studies on `/graph/groups` now contain Group-private Discussion topics and
chronological replies. Collaboration owns both aggregates; Space remains the fresh
membership/role authority. This implements the planned Discussion domain without an
architecture exception, new dependency, dataset or service.
DiscussionReply is a message of the Collaboration-owned conversation; the
Content-owned Comment domain for content is unchanged.

## Sharing, role and lifecycle

- Current registered OWNER/MEMBER creates topics and replies after explicit Group
  sharing confirmation. Current VIEWER can read all shared conversation/history.
- Only current OWNER locks/reopens/archives a topic, including one authored by MEMBER.
- OPEN <-> LOCKED; RETIRED is irreversible. Locking/retirement stops new replies.
  RETIRED Study blocks new topics, replies and actual moderation changes.
- COMPLETED Study is still writable; only archival freezes it, as with other children.
- Original title/body and replies are immutable in this slice. Corrections are new
  replies. Archival preserves topic/reply/moderation history for authorized readers.
- GROUP_MEMBERS means current Group participants, including later participants. It
  never widens the separate AUTHOR_AND_OWNER audience of Assignment Submissions.
- No automatic personal Note/evidence/file import, Submission reference, notification,
  edit/erase of another author's text, nested reply tree or public publication.

## Concurrency and recovery

Topic creation uses a 32-hex Idempotency-Key scoped Study + creator. Exact original
title/description returns the current snapshot after state changes, parent archive
or restart; changed original input under that key is409. First create201, retry200.

Replies use a separate 32-hex key scoped Discussion + author and `If-Match: "diN"`.
Exact original content/observed version returns the same immutable reply, even after
lock/archive. New key is an explicit new reply, even for identical text. Changed
intent is409, stale observed open Discussion version412, missing precondition428.
Current writer role is checked on every retry. Replies have their own UUIDv7/version1
and pin the local Discussion revision. Neither Discussion nor Study version advances
when a reply is added. Owner moderation uses the independent strong diN version.
Exact current-version no-op creates no event/revision; old versions still fail412.

All writes lock ACTIVE Group, Study, then Discussion inside one Collaboration
transaction. Study archival and moderation use these same locks. Current snapshot,
immutable moderation revision/reply and Outbox commit or roll back together. There
are only local FKs; no network calls while locked or cross-service database access.

## APIs and operation

- [OpenAPI](../contracts/openapi/study-discussions.yaml)
- [Protobuf](../contracts/events/src/main/protobuf/collaboration_discussion_v1.proto)
- GET/POST `/api/groups/{groupId}/studies/{studyId}/discussions`
- GET/DELETE `.../discussions/{discussionId}`; PUT `.../{discussionId}/state`
- GET `.../{discussionId}/revisions`
- GET/POST `.../{discussionId}/replies`; GET `.../replies/{replyId}`

V6 adds study_discussion, study_discussion_revision and discussion_reply. Applied
migrations/published schemas stay unchanged. DiscussionCreated/StateChanged/Retired
events use collaboration.discussion.v1; DiscussionReplyCreated uses
collaboration.discussion-reply.v1. They share discussion-events/1 and envelope,
with distinct aggregateType and payload messages. Connect alone publishes from the
existing local Outbox/CDC route. Consumers must preserve Group authorization,
deduplicate eventId, and apply increasing aggregate versions. No new projector is
introduced. Group validator is the only new literal reader; no offsets reset.

Bodies max65536 bytes, title<=200 Unicode code points/no controls, body/reply<=32768
UTF-8 bytes/no NUL, nonblank reply. Strict duplicate/trailing/extra JSON and query
validation. Catalog20/max100 metadata; full replies5/max10 and history5/max10 fit
the existing bounded Gateway transport. UUIDv7 keysets and descending version history.
Browser state stays in memory and clears/aborts on hide/unmount/denial. Stale reply
input is retained until explicit refreshed context and sharing consent. Unknown
responses preserve request keys. All text renders literally. Logs contain fixed
action, aggregate ID and trace, never body/cookie; read/change metric labels are
fixed. Existing DB pool/probes and HTTP/event trace propagation remain in use.

Use backend `--collaboration --group-kafka`, Gateway tests and browser `--discussions`
with Group regressions. Results: `.local/discussion-validation.json` and
`study-discussions-validation.txt` after actual local Kubernetes verification.
