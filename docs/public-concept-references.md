# Private Note references to public Concepts

Personal Brain references canonical public Concepts without duplication, as already
required by PROJECT.md. This extends the existing Note Concept reference API; it
does not publish the Note, create public backlinks, or grant contribution rights.
Guest and registered principals may both link readable public knowledge.

## Ownership and authorization

Content still owns Note relationships, independent cN versions, stable linkId,
immutable history and transactional Outbox. Each item contains only linkId and
conceptId. The Space in the relationship/event is the PRIVATE Note's Space.
Knowledge alone queries its Concept database. No cross-service SQL, foreign keys,
copied definitions, new database migrations or Protobuf changes are introduced.

For additions, Content verifies destination Note write access, then invokes its
existing HTTPS scoped Knowledge authority with the common opaque session token.
Knowledge first checks destination Space CONCEPT_READ, then resolves only ACTIVE
same-Space PRIVATE or canonical PUBLIC IDs. Each distinct PUBLIC Space requires
fresh PUBLIC_CONCEPT_READ from Space, including its active public catalog check.
All decisions must resolve the same principal. Foreign PRIVATE, RETIRED, unknown
and proposal-only IDs fail without exposing text. Authority failure prevents the
entire write; no partial batch is saved. The batch remains limited to 20 unique IDs.

Existing references can be read, retained, reordered and removed during Knowledge
outages or after target retirement; destination Note authorization remains mandatory.
Removing and re-adding requires fresh target validation. A public revision does not
change link IDs or Content's cN version. As before, retirement concurrent with a
synchronous check may race: service transactions remain local, and an ID reference
is not a guarantee of continued target availability.

## User flow

At https://localhost:8443/graph select a Note, then use **공개 개념 연결** in its
inspector. Select Medicine or Development and check the desired canonical Concept.
The list loads 20 items per page with a more button; the title filter applies to
loaded items. This is a catalog picker, not the future public search projection.
Retained references outside the current page/domain remain removable and their
titles are resolved through authorized public APIs. Failed lookup falls back to
an ID label. Space/Note changes abort pending requests and clear prior private state.

## Validation and local rollout

Run Java 25 checks with the existing Kubernetes runners:

```powershell
python scripts/Run-BackendValidation.py --knowledge --knowledge-kafka
python scripts/Run-BackendValidation.py --content --content-kafka
python scripts/Build-LocalSpaceImage.py --service knowledge
python scripts/Build-LocalSpaceImage.py --service gateway
python scripts/Import-LocalSpaceImage.py --service knowledge
python scripts/Import-LocalSpaceImage.py --service gateway
```

Wait for both imports on every local node. Deploy with
`Install-LocalPublicConceptReferences.py --helm <existing-helm-executable>`.
It validates completed image import Jobs and changes only Knowledge and Gateway.
Content storage/events and existing CDC/Registry transport remain unchanged.

`Run-GraphWebValidation.py --public` creates canonical data using the real
contribution/review/merge workflow, with scoped operator reviewer grant/revocation.
It exercises registered mixed references, unmerged and foreign-private denial,
revision independence, stable IDs on reorder, guest UI persistence/removal and
private Note isolation. Desktop/mobile screenshots use verified public CA trust.

After a canonical public fixture exists, run
`Run-NoteConceptApiValidation.py --public --failures`, then pass the printed Job to
`Run-NoteConceptApiFailureValidation.py --job <job>` immediately. This replaces only
Knowledge Pods and always restores the original replica count, verifying retained
public reference reads/reorder/removal while additions fail closed and recover.
The optional --public fixture is read through APIs, never another service's DB.

Concrete results are recorded in public-concept-references-validation.txt.
Graph rendering redesign, public search/graph projections, Claims/Aliases and
clinical verification remain separate work.
