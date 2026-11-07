# Retired personal Source archive

Open `/graph` → **개인 출처 보관함 열기** → **폐기한 출처 열기**.
Twenty-row creation-order pages rediscover retired private Sources even after
closing the detail or reloading the browser. Stable IDs distinguish duplicate
titles. Select a Source for its immutable title, URL, citation and license notes,
revision IDs, actor IDs and timestamps. History loads five revisions per page.

The existing **이 출처를 인용한 관계** panel is available below the archive
history. It discovers effective active citations even when their Source is
retired and retains known citation history through subsequent lifecycle changes.
Citation-pinned Source metadata remains its original snapshot, independent of
the selected Source revision. Retiring a Claim or its referenced Concepts can
hide that citation from current discovery, as before. This feature does not add
discovery for retired citations or automatically re-pin evidence. Directly
unlinked Citations are instead rediscovered from their Claim's independent
[unlinked Citation archive](retired-citations.md).

Active Source editing and new Source drafts remain independent. Archive actions
perform GET requests only. Text is escaped, safe HTTP(S) links require explicit
navigation with noopener/noreferrer, and URLs are not fetched automatically.
Nothing is persisted in browser storage. Failed reads clear stale results;
late replies cannot repopulate a closed or changed scope. Denied authority clears
private UI. Returned Space/Source IDs, PRIVATE/RETIRED state, field bounds, URLs,
cursor ordering and revision identities are validated. The first history page
must match every retired metadata field and version in the selected row; repeated
revision IDs within or across pages are rejected.

## Contract and compatibility

`GET /api/spaces/{spaceId}/sources?state=RETIRED` returns the existing Source page
shape. State is optional and defaults to ACTIVE. Each request performs fresh
Space/Identity authorization and returns no-store. UUIDv7 `after` is exclusive;
limit is 1..100, default20. Unknown, duplicate and invalid parameters fail.
Gateway allows state only on the exact private Source collection GET; Source
detail/history/citation and write routes retain their existing query contracts.
Current Source GET/PUT/DELETE stay active-only. Known immutable history is retained.

AGENTS.md and PROJECT.md were reviewed; there are no decision conflicts.
Knowledge owns canonical data and all SQL. V14 adds only a partial
`(space_id, source_id)` index over RETIRED Sources. Reads leave Source, Citation,
revision and Outbox data unchanged. No event, projection, checkpoint, dependency
or dataset changes. Bounded read metrics add `type=retired_list` to
`brain.knowledge.source.reads`; existing HTTP tracing covers the path.

## Local delivery

Run `Run-BackendValidation.py --knowledge --knowledge-kafka` and the separate
`--gateway` validation. Build/import Knowledge and Gateway using existing scripts,
verify both local node imports, deploy Knowledge before Gateway using the exact
generated tags and existing Helm values. No projection rebuild is needed.
Run `Run-GraphWebValidation.py --retired-sources-regressions --no-images` against
the deployed HTTPS application. Recorded results: `retired-sources-validation.txt`
and `.local/retired-sources-proof.json`.

## Compare retained revisions

Personal Concept and Source histories now compare any loaded earlier/later pair,
including retired archives, with exact fields and audit metadata. See
[knowledge-revision-comparison.md](knowledge-revision-comparison.md).
