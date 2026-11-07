# Private Concept and Source revision comparison

On `/graph`, open a personal Concept's **개인 개념 수정 이력** or a Source's
**출처 수정 이력**. The comparison defaults to the two newest loaded revisions.
Select an earlier base and later target, then optionally show only changed fields.
Concepts compare title, description and state; Sources compare title, URL,
citation, license memo and state. Each side includes the stored revision ID,
actor and timestamp. The retired Concept and Source archives expose the same
comparison, including the ACTIVE-to-RETIRED transition.

Comparison uses exact stored values. Whitespace, line breaks, Unicode and URL
spelling are not normalized. An absent Source URL is displayed as `미입력 (null)`;
empty strings are labelled separately. Identical content in distinct revisions
reports zero changed fields. A single revision or a same/reversed pair has an
explicit unavailable state. Loading previous pages retains the chosen pair;
refreshing, selecting another item or reopening starts with the newest pair.

History readers validate aggregate/revision/actor IDs, field sizes, lifecycle,
dates, descending versions and exclusive cursors. Duplicate revision IDs within
or across pages cause the whole read to fail rather than silently discarding
rows. Initial or next-page failures clear old comparisons and support explicit
retry. Closing/switching aborts requests and discards late replies. Fresh authority
denial clears private parent state. Existing independent editor drafts survive
comparison navigation. Nothing is stored in browser storage.

URLs in the comparison are escaped metadata, not links or automatic requests.
Comparing does not modify canonical data, restore a revision, repin evidence or
alter verification. Existing Citation and External Mapping pins remain immutable.
Both columns represent stored historical versions, never freshly fetched current
metadata. Tables wrap text and collapse controls/audit columns on narrow screens.

AGENTS.md and PROJECT.md were reviewed before implementation; no established
architecture decision conflicts were found. Knowledge remains the canonical
owner, and existing HTTPS revision APIs retain fresh Space/Identity authorization
and no-store semantics. Existing read metrics and traces cover these reads.
There are no API/event/schema changes or new dependencies/datasets. This change
requires only a Gateway static frontend build and rollout.

Build with `Build-LocalSpaceImage.py --service gateway`; wait for its builder,
import the exact image on both local nodes and upgrade the existing Gateway
release. Run `Run-GraphWebValidation.py --knowledge-comparison-regressions
--no-images`. Six new browser scenarios cover exact comparisons, draft/pin
preservation, pagination, archived navigation, invalid replies and revocation.
Existing editor, retirement, archive and comparison scenarios run alongside them.

Results: [knowledge-revision-comparison-validation.txt](knowledge-revision-comparison-validation.txt).
