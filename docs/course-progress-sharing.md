# Explicit owner-only Course progress summary sharing

Decision2026-10-24: the Group OWNER can enable sharing, and each participant must
separately consent before a completion-rate summary is shown to the current OWNER.
No other participant or public audience receives it. Existing private Course progress
and its event contract remain PRIVATE/PARTICIPANT_ONLY.

## User flow

On `/graph/groups`, select a Course and open the Course progress section. The OWNER
can enable **Group summary sharing** for all Courses in that Group. Default is off.
Participants see their saved completed/total count, explicitly check consent, then
share. The OWNER sees only participant ID, completed/total count, percentage derived
from those counts and shared time. No individual Study status, title, private progress
ID/history, personal Note, session, evidence or attachment is disclosed.

A publication is a snapshot. Later private progress changes do not update it; the
participant explicitly re-shares after reviewing the new preview. Share withdrawal
is always available to a current member, including VIEWER, after policy disablement,
enrollment withdrawal or Course retirement. It does not change private progress.
Removed Group participants cannot read or mutate retained records until authorized again.

Turning the policy off immediately hides summaries on subsequent reads. Turning it
back on advances the policy version, and old summaries stay hidden until re-consent.
Role changes/removal/reinvite and Course withdrawal/rejoin similarly invalidate the
captured membership/enrollment versions. Returning to the same role does not restore
consent. Retired Course disallows new publication; prior eligible snapshots may still
be read and their authors can withdraw them. Completion is self-reported, not grading.

## Ownership, consistency and API

- Space V7 owns Group policy, immutable revisions and Outbox. Current Group member
  reads, current OWNER alone changes policy or reads policy history. gpp0 is an
  absent nonpersistent DISABLED sentinel. Strong gppN protects writes. No-op is stable.
- Collaboration V8 stays unchanged. A read-only sharing-context POST combines one
  bounded Space policy/current-members snapshot with Course/enrollment data in its
  own local transaction. It intersects requested active members with current ENROLLED
  rows. Nonempty batch requires OWNER; max100 distinct UUIDv7 IDs. No N+1 HTTP checks.
- Learning V12 owns explicitly published count snapshots, revisions and Outbox.
  Share has a separate UUIDv7 and strong csN version. Absent own share is null/cs0;
  initial withdrawal creates nothing. Publish requires current OWNER/MEMBER, ENROLLED,
  Course ACTIVE/COMPLETED, enabled policy, saved progress, exact observed policy,
  membership, Course, enrollment and progress versions. No-op never emits a new event.
- Learning serializes private progress and publication under the same local per-scope
  advisory lock. Preview and source-version checks run inside the local transaction.
  Current snapshot, revision and Outbox either commit together or roll back together.
- Own share/history routes have no caller-controlled participant selector. OWNER list
  scans at most limit+1 Learning snapshots then performs one fresh batch authority call.
  It filters pinned policy/membership/enrollment versions and returns minimal summaries.
  Cursor advances by last scanned share ID, so a filtered empty page can have nextAfter.
- Current Course metadata change requires a fresh publication preview but does not
  silently retract earlier consent. Policy/membership/enrollment epochs control visibility.
- HTTPS trusted CA, no redirects, singleton delegated browser cookie and trace; no caller
  Authorization/actor header. Space service credential is the existing scoped Group key.
  Exact duplicate-free JSON, bounded bodies/authority responses, strict IDs/version fields.
  Fresh authorization before local tx, no cache/distributed transaction: a concurrent
  revocation can race an admitted request; subsequent reads/writes recheck authority.
- Browser values are rendered as text and retained in memory only. Hide/unmount or
  denial aborts requests and clears state. Unknown/stale outcomes require explicit
  refresh/review. New scopes expose no owner controls or list to MEMBER/VIEWER.

See `contracts/openapi/course-progress-sharing.yaml` for every request/response,
status, independent ETag, source-context field and bounded cursor. Default pages20/max100;
revision pages5/max10. Trace flows HTTP -> local Outbox -> Kafka. Bounded metrics:
`brain.space.group.progress.policy.*`, `brain.collaboration.course.sharing.context.reads`,
`brain.learning.course.summary.*`. No payload, browser token or personal content logging.

## Separate events and operations

`space.group-progress-policy.v1`, Registry `space/group-progress-policy-events/1`,
SHA256 `755d777446329a305692fdc0cdd2825e71cc6a32001d2dacb24631ac5162f040`.
`learning.course-progress-share.v1`, Registry `learning/course-progress-share-events/1`,
SHA256 `5833449160244e12ae9bb346d998c9dab13ed9eb43e90bf2f5e9c328af15fee6`.
FULL validity/FULL_TRANSITIVE compatibility. Immutable old private-progress proto is
unchanged. Snapshots include publication/withdrawal state, count-only payload and
observed consent provenance. Connect is the only literal topic writer; Group/Learning
validators are scoped readonly readers. No application producer or new projector.
Consumers must deduplicate eventId, apply increasing versions and re-authorize before
disclosure; an event is not a lasting visibility grant. No offsets/slots/publications reset.

Canonical tables are unreadable by CDC credentials, while existing Outboxes remain
readable. New FKs are local only: Space policy to local Group/Space; Learning share
to exact local progress scope and its revisions. No new dependency, dataset, AI,
cross-service SQL/FK, Personal Space copy or architecture exception.

Validation: `docs/course-progress-sharing-validation.txt` and
`.local/owner-summary-validation.json` record actual tests/deployment/CDC/browser evidence.
Run existing backend validation with --space --group-membership-kafka, --collaboration
--group-kafka, --learning --learning-kafka and --gateway. Run graph validation with
--owner-summary plus the existing Group/Course/progress scenarios. Use
`scripts/Run-OwnerSummaryContractValidation.py` for immutable Registry validation.
