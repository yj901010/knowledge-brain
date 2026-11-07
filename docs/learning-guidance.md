# Deterministic private Learning guidance

`GET /api/spaces/{spaceId}/roadmaps/{roadmapId}/learning-guidance` adds read-only
next-study guidance to the existing Personal Roadmap. Each request uses fresh
`ROADMAP_READ` authority for the current PRIVATE PERSONAL Space and returns
`Cache-Control: no-store`. The same browser principal supports guests and
registered users. Query parameters and request bodies are rejected. The exact GET
is the only new method/path; public and Group aliases are absent.

Learning reads the current immutable graph revision, its active node/edge heads,
and active-node manual progress with its current immutable revisions in one
Learning-owned PostgreSQL statement. Lateral bounded aggregates return the graph
once and share one MVCC snapshot with progress. IDs, versions, visibility, state,
metadata, graph bounds/semantics and canonical/revision bindings are validated;
corrupt or unavailable canonical input fails closed with a private503. The graph
retains its existing100-node/500-edge/512KiB compact bound. PostgreSQL's larger
`jsonb::text` representation has a separate bounded1MiB read allowance and is
checked against the original compact bound after decoding.

`GRAPH_PROGRESS_V1` applies only direct incoming edges:

- Unfinished `PREREQUISITE_OF` and `REQUIRED_FOR` sources put an unfinished node in
  `waiting`. This guidance never restricts existing manual progress commands.
- Unfinished `RECOMMENDED_BEFORE` sources supply a soft ordering count and
  explanation. `PART_OF`, `OPTIONAL_FOR` and `RELATED_TO` imply no completion or
  priority.
- `COMPLETED` nodes are excluded from suggestions/waiting. Missing progress stays
  a nonpersistent `NOT_STARTED`/`pr0` sentinel. Explicitly completed B satisfies
  C's direct B prerequisite even when A→B remains unfinished.
- Eligible suggestions sort `IN_PROGRESS` first, then fewer distinct unfinished
  recommended predecessors, then UUID string ascending. Waiting and predecessor
  rows sort by UUID string; relation kinds sort alphabetically. Multiple kinds
  for one predecessor are retained without double-counting.

The response includes `roadmapId`, `spaceId`, `visibility`, `roadmapVersion`,
`algorithm`, `basis`, `suggestions` and `waiting`. Basis rows contain every current
active `nodeId`, manual `status` and independent `progressVersion`. Suggested and
waiting rows add saved `title`, `unmetRecommendedCount`, `requiredPredecessors`
and `recommendedPredecessors`. Each predecessor includes the same identity,
saved title, status/version and retained `kinds`. The combined result stays within
the graph bounds; see the additive OpenAPI contract for the exact schema.

No table, migration, event, cache, dependency, cross-owner SQL, automatic progress
or publication is introduced. Existing `rmN`, `prN`, sessions and evidence remain
independent. Study time, notes and evidence never infer progress or ranking.
Successful reads increment only `brain.learning.records{operation="guidance-read"}`;
there are no principal, Space, roadmap or content metric labels.

The private UI places 다음 학습 안내 after the completion summary. It shows a few
ranked suggestions with expand-all and required/recommended explanations.
Selecting a node opens its original freshly read progress detail. Successful
manual progress and saved graph-version changes refresh guidance without
remounting session/evidence siblings. Guidance uses saved current graph state,
with the existing cancellation, denial, principal and Space boundaries.

Focused pure, API/security and real owner-database tests cover relation semantics,
ordering, permutation invariance, bounds, no mutations, one-statement concurrent
coherence, immutable revision corruption and session/evidence independence. The
database tests use the existing `BRAIN_TEST_LEARNING_DATABASE_URL` gate and require
an isolated Learning owner database. Deployment/browser/scan evidence belongs to
the package validation record; this document alone does not assert rollout.

Final source/image/browser/preservation results and limits are recorded in
[the package validation](guidance-curation-validation.txt).
