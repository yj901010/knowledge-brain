# Public knowledge contribution, review and verification

Open <https://localhost:8443/graph/knowledge>. The existing Concept proposal UI is
retained. The new **공개 지식 기여·검증 작업실** below it provides public Alias,
Claim, Source, Evidence, Mapping and Verification catalogs, a filtered review
queue, explicit proposal forms, current/proposed comparisons and revision history.
Guests may browse; registered accounts may propose. The form confirms that its
content, rationale and review history will be public.

## Publication and revisions

Every CREATE, UPDATE and RETIRE follows SUBMITTED -> APPROVED -> MERGED, or
SUBMITTED -> REJECTED. Approval alone changes no canonical data. Only a current
operator-designated public-Space REVIEWER/ADMIN/OWNER may review and merge, and
never their own proposal. Both decisions and merge require a nonempty public
reason. Immutable proposals use strong `kpN` ETags; canonical snapshots use `krN`.
Stale proposals/references fail412; equivalent active facts and invalid transitions
fail409. Retired rows and original revisions remain readable.

Only explicitly entered public fields and current same-public-Space references are
accepted. There is no private-to-public import, private ID dereference or automatic
Note publication. Concept IDs are the existing public Concept IDs. Alias scope is
GLOBAL, COUNTRY (two uppercase country-code characters), or ORGANIZATION with an
explicit scope value. A local alias does not silently become global.

Claims retain subject/predicate Concept revision pins and a CONCEPT, STRING,
NUMBER, DATE or BOOLEAN object. Source metadata retains title, URL/citation and a
mandatory license/use note. Source URLs are stored, never fetched by this writer.
Evidence retains the exact Claim and Source versions, original locator and an
explicit explanation. It is not an upload or a copy of the original full text.

Proposal, review, canonical revision and both Outbox entries are one Knowledge
database transaction. References are rechecked at merge. Writes serialize within
the public Space, and public Concept dependency rows are locked while inspected.
Hashed canonical identity tuples keep long Unicode literals and delimiter-bearing
values out of wide/ambiguous unique keys. V19 backfills earlier workbench rows and
normalizes rolling older writers; existing private records/revisions are unchanged.

## Verification and mapping are independent

Each Claim revision starts with effective DRAFT verification. A reviewed assessment
progresses SOURCED -> COMMUNITY_REVIEWED -> EXPERT_VERIFIED, at most one upward
stage per proposal. It pins an active Evidence version for that exact Claim revision
and its inspected Source. Changing the Claim does not transfer an old assessment.
Changing/retiring the cited Source or Evidence makes the old assessment require
review and its effective level DRAFT; historical assessment/revisions remain.

Mappings progress CANDIDATE -> CONFIRMED -> OFFICIAL independently. Confirmed and
official entries require an external edition/version and explicit authority/provenance.
A Mapping records exact Concept/Source revision pins. An official Mapping does not
verify any Claim, and an expert Claim assessment does not make a Mapping official.
Retained Mapping state describes its inspected pins, not an automatic endorsement
of subsequent referenced revisions or every term in an external vocabulary.

Expert verification and official Mapping approval/merge additionally require
separate active Space-owned capabilities:

- `CLAIM_EXPERT_VERIFY`
- `MAPPING_OFFICIAL_REVIEW`

A reviewer membership alone grants neither. Both approval and merge perform fresh
Space/Identity authorization. Revoking a capability after approval denies merge.
The application records an operator's qualification/provenance assertion; it does
not independently authenticate professional credentials or award qualifications.
The validation fixtures use Development-only example vocabularies, not medical
expert credentials or real official terminology claims.

## Operator authority

The existing reviewer tool remains authoritative for membership. First register
the account and use its application principal UUIDv7 (not its Keycloak subject):

```powershell
python scripts/Set-LocalPublicReviewer.py --principal-id APPLICATION_UUIDV7 --domain DEVELOPMENT
python scripts/Set-LocalPublicCapability.py --principal-id APPLICATION_UUIDV7 --domain DEVELOPMENT --capability CLAIM_EXPERT_VERIFY --provenance "Operator-reviewed qualification and scope reference"
```

Use MEDICINE only for an appropriately assessed medical-domain reviewer; record the
actual scope/provenance. Grant `MAPPING_OFFICIAL_REVIEW` separately when authorized
to attest official provenance. No browser route grants membership/capabilities.
No normal account is automatically promoted. The helper is restricted to the source
k3d context and uses only Space's own credential in a short-lived Job. Capability
state, immutable audit and typed Outbox event commit together. No credential is
printed; provenance must contain no secrets.

Use the same capability command with `--revoke` and a reason in `--provenance` to
withdraw that authority. Retire capabilities when withdrawing their qualification,
then retire membership with the existing reviewer's `--revoke`. Membership and
capability are independent records: inactive membership blocks all use, while a
later reinstatement also requires the capability still to be active. Explicitly
retired capabilities never reactivate merely because membership is reinstated.

## Public search and relation browsing

Search public Concept titles and reviewed Alias labels with a literal, bounded
query. Canonical fact catalogs have type/state filters, and proposals have
type/state/my-proposals filters with UUID keyset paging. Full text is read only on
selection. History pages contain five entries by default and at most ten.

The relation view reads at most20 merged incident Claims and their public Concept
summaries, with a `truncated` indicator. It includes typed literal objects. These
are bounded authoritative reads inside the Knowledge owner's database; they do
not cross-service join or claim complete public OpenSearch/JanusGraph projections.
Large graph visualization and the agreed Obsidian design remain their own phase.

## Deploy and validate

Run `Prepare-PublicKnowledgeWorkbench.py --helm PATH` before any new writer: it
creates two explicit Kafka topics and scoped ACLs, and registers immutable
Protobuf artifacts with FULL/FULL_TRANSITIVE rules. Existing schemas are unchanged.

```powershell
python scripts/Prepare-PublicKnowledgeWorkbench.py --helm C:\path\to\helm.exe
python scripts/Install-LocalPublicKnowledgeWorkbench.py --helm C:\path\to\helm.exe
python scripts/Run-BackendValidation.py --knowledge --knowledge-kafka
python scripts/Run-BackendValidation.py --space
python scripts/Run-BackendValidation.py --gateway
python scripts/Run-GraphWebValidation.py --public-workbench --serial
```

The installer independently builds/imports Space, Knowledge and Gateway on both
nodes, rolls one service at a time and retains the OpenTelemetry overlay. Source
and image hashes, immutable identities and successful import logs are captured
before only the owned temporary builders are cleaned. `--refresh-service NAME`
rebuilds that release when an actual follow-up change requires it.

Knowledge migrations V18/V19 add public snapshots/proposals/reviews/revisions and
canonical identity normalization. Space V8 adds independent capabilities/audit.
The six public fact kinds share the owning Knowledge aggregate/review lifecycle;
private working stores retain their existing PRIVATE contracts and histories.
No service/database ownership, communication or deployment architecture changes.

New events:

- `knowledge.public-record.v1`, Registry `knowledge/public-record-events` V1:
  proposal transitions and canonical record revisions, complete typed public JSON.
- `space.public-capability.v1`, Registry `space/public-capability-events` V1:
  explicit operator capability assignments/withdrawals with provenance.

The only publisher remains Outbox -> Connect -> Kafka. Consumers deduplicate
eventId and order per aggregate; canonical record and proposal IDs differ and
cross-aggregate ordering is not promised. Capability events do not replace fresh
authorization. Existing private and Concept event contracts are immutable.
No new canonical consumer/projection is introduced.

The browser test creates isolated registered/guest fixtures, grants its own test
reviewer only, and retires granted capabilities and membership in `finally`.
It tests actual approval/merge, independent authority, mid-workflow capability
revocation, stale references, immutable histories, private/guest denial, scoped
Alias search, relation browsing and390px layout using verified HTTPS.
`PublicRecordKafkaIntegrationTest` compares actual committed bytes/headers/trace
and version order. After the browser run, its reviewer ID can also be checked with:

```powershell
python scripts/Run-BackendValidation.py --space --space-kafka --public-capability-principal TEST_REVIEWER_UUIDV7
```

This reads actual operator events and verifies exact Space CDC bytes; it does not
grant new authority. See [OpenAPI](../contracts/openapi/public-knowledge-workbench.yaml)
and [validation evidence](public-knowledge-workbench-validation.txt).

No package versions, third-party datasets or clinical content were imported. Source
license notes are submitted provenance, not blanket redistribution permission.
Curated licensed medical/development seeds, public qualifiers and expanded graph
projections remain separate delivery work. The13-service MSA, private defaults,
Outbox-only publication and V1 no-AI decision remain unchanged.
