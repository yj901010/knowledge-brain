# Public Concept contributions

Implemented locally on 2026-10-09. Open https://localhost:8443/graph/knowledge
from the Graph page's public-knowledge link. Guests can browse the public
Medicine and Development catalogs. Registered accounts can submit explicit
CREATE or UPDATE Concept proposals. Their existing private Notes, Concepts and
Space IDs remain private and unchanged.

## Workflow and authorization

The user-approved policy is recorded in
[decisions/0003-public-concept-review.md](decisions/0003-public-concept-review.md).
Space owns the public catalog, membership and permissions. Knowledge owns
proposals, review history, canonical Concepts and revisions. There is no
cross-service SQL or foreign key. Every read/write receives fresh Space
authorization backed by fresh Identity session validation.

Only registered accounts may propose. Only active operator-designated REVIEWER,
ADMIN or OWNER memberships of that public Space may approve/reject/merge. An
author cannot review or merge their own proposal. A browser cannot assign roles.
Existing private CONCEPT_WRITE does not authorize public canonical mutations.

Proposals are immutable snapshots with an explicit public title, Markdown text,
rationale, author and canonical base version. They follow SUBMITTED -> APPROVED
-> MERGED, or SUBMITTED -> REJECTED. Approval alone changes no canonical Concept.
Rejecting requires a reason; revised text requires a new proposal. Strong pN
ETags serialize competing review/merge requests. UPDATE merge also locks and
checks the canonical base version, so a stale approved proposal cannot overwrite
a later revision. A successful merge retains the Concept ID and appends a new
canonical revision. CREATE reserves a new Concept ID at submission but publishes
no canonical row until merge.

All canonical mutations, contribution state, review audit and both Outbox rows
commit in one Knowledge database transaction. Failed event insertion rolls back
the entire merge. Duplicate/stale retries fail without additional revisions.
Cross-topic consumers must not assume ordering between Concept and Contribution
topics. Text is displayed as plain text; Markdown HTML is not executed.
Catalog pages contain bounded summaries; full proposal text is fetched only
when selected, keeping maximum-size descriptions within Gateway response limits.

Public proposals, their rationale, reviewer IDs and review reasons are publicly
readable to browsing sessions. The form explicitly says the submitted text is
public. No private content is automatically copied. Medical Concept text review
does not grant clinical verification, expert status or terminology mapping.

## Operator-designated reviewer

Create/login the intended registered reviewer account first. Its application
principal UUIDv7 is returned by GET /api/identity/account-session. It differs
from Keycloak's subject ID. In that account's browser console, this read-only
command displays it:

```javascript
fetch('/api/identity/account-session').then(r => r.json()).then(s => console.log(s.principalId))
```

From C:\dev\knowledge-brain, an operator grants one public domain explicitly:

```powershell
python scripts/Set-LocalPublicReviewer.py --principal-id <APPLICATION_UUIDV7> --domain DEVELOPMENT
```

Use MEDICINE for the other domain, or add --revoke to retire that reviewer role.
The helper is restricted to k3d-knowledge-brain. Its short-lived Job uses only
Space's own database owner credential. It checks an existing registered guest
claim, active public catalog and conflicting membership roles. Role mutation and
Protobuf Outbox insertion are atomic; credentials are not printed or copied into
ConfigMaps. Existing OWNER/ADMIN roles cannot be overwritten by this helper.
Revocation affects the next authorized request, even if an older UI shows a
review button. Validation only designates its freshly created test account and
retires that role in finally; normal accounts are never auto-promoted.

## Deployment and contracts

Space V3 seeds two public catalog Spaces with stable UUIDv7 IDs. Knowledge V2
adds immutable proposals/reviews and expands Concept visibility to PRIVATE/PUBLIC.
Existing private canonical rows and revisions are retained. The public API is
controlled by brain.knowledge.public.enabled; local and production Helm values
use the same service/event/database boundaries. Production supplies its own
origins, secrets, images and reviewer provisioning.

- Browser contract: contracts/openapi/public-contributions.yaml.
- Space authority: contracts/openapi/space-knowledge-authorization.yaml.
- knowledge.contribution.v1: knowledge/contribution-events V1.
- space.public-knowledge.v1: space/public-knowledge-events V1.
- Merged canonical revisions retain knowledge.concept.v1 and its immutable V1
  contract, using PUBLIC visibility in the existing string field.
- New contracts use FULL validity and FULL_TRANSITIVE compatibility. Kafka
  Connect can write only the declared topics; the validator has read-only ACLs.
- CDC still reads only each service's own Outbox. No new canonical consumer or
  read projection is introduced. Event IDs, actor IDs and trace IDs are retained.
- Contribution metrics use bounded type labels; audit logs include state,
  contribution ID and trace ID, never browser credentials or proposed text.

Build/import Space, Knowledge and Gateway using Build-LocalSpaceImage.py and
Import-LocalSpaceImage.py --service NAME. Wait for imports on both local nodes.
Then run Install-LocalPublicContributions.py --helm PATH. It verifies successful
imports, creates topics/ACLs and registers contracts before deploying writers,
restarts only failed CDC tasks and waits for all three services to be Ready.

Keep Start-LocalGateway.ps1 and Start-LocalLogin.ps1 running in separate terminals
as described in [registered-login.md](registered-login.md).

## Verification and remaining scope

Run-BackendValidation.py with the existing PostgreSQL/Kafka/CDC/storage flags
covers atomic merge rollback, concurrent review, stale canonical versions,
private target denial, self-review and live reviewer membership. The contribution
Kafka integration test compares exact committed bytes, topics, schema headers,
trace IDs and per-contribution version order with actual Debezium output.

Run-GraphWebValidation.py --public uses actual HTTPS and Keycloak registration
for two isolated accounts. The host operator grants only its test reviewer,
checks guest/ordinary-account denials, CREATE and UPDATE through review/merge,
canonical revision history, private/public isolation, text escaping, mobile
layout and live role revocation. Public CA trust is installed; TLS verification
is never disabled. The role is retired even if the test fails.
The actual operator role event bytes can also be checked with:

```powershell
python scripts/Run-BackendValidation.py --space --space-kafka --public-reviewer-id <TEST_REVIEWER_UUIDV7>
```

Use the fresh reviewer ID printed by the browser run; this checks its one grant
and one revocation against the committed Space Outbox and generated Protobuf.

Public Note-to-Concept selection is delivered in public-concept-references.md.
The later [public knowledge workbench](public-knowledge-workbench.md) delivers
reviewed Aliases/Claims/Sources/Evidence, independent verification and mapping
authority, a review queue and bounded canonical search/relation browsing. This
original Concept milestone remains unchanged. Public qualifiers, curated licensed
content and broader search/graph projections remain separate work.

Concrete local evidence: public-contributions-validation.txt.
