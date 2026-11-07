# Private version-pinned submission attachments

User approved 2026-10-25: newly uploaded submission files are visible only to the
author and current Group OWNER, matching the private answer policy. No personal
Media/Note/evidence is imported. Removing the author revokes new access; the current
OWNER retains the submitted work. VIEWER can read their own old files, cannot upload.

On `/graph/groups`, open Group -> Study -> Assignment -> 과제 제출. Choose a new file,
upload, inspect the explicit list and confirm the answer. At most5 files, each1..10MiB.
Media's existing100MiB allocation/10pending limit applies to the Group Space. Excluding
a file from the next submission does not delete bytes or reclaim quota. Uploads alone
do not share a file with the OWNER. Submission is the explicit sharing action.

## Ownership and consistency

Media owns all metadata and sealed, immutable MinIO object versions. New V2
domain_media_allocation binds an opaque consumer context/owner and allocation key
to own Media rows. Media does not interpret Assignment or membership semantics.
Collaboration holds the dedicated TLS service credential and rechecks registered
Group participation through Space on every browser request; it owns the submitted
relationship. No service queries another owner's database. No cross-service FK,
distributed transaction or Kafka producer is introduced.

Client uploads bytes directly using a5minute MinIO POST form. Completion verifies
size, MIME and SHA256 and seals the version internally. The consuming domain accepts
only its exact context/author AVAILABLE version2. A guessed personal file ID, another
author's allocation or another Assignment's file fails without metadata disclosure.
The old Personal Media endpoints still authorize PERSONAL/PRIVATE only.

V16 adds immutable submission_file_bundle, one UUIDv7/version1 per exact Submission
attempt with a nonempty ordered snapshot. Local FKs bind retained attempt and full
Submission/author/Group/Space tuple. Group -> Study -> Assignment -> Submission locks
and one Collaboration local transaction commit answer/attempt/bundle/both Outboxes
or roll everything back. Media checks precede locks. Concurrent membership revocation
can race an already admitted operation, as with other Group commands.

The additive `/submissions/with-files` command retains strong suN and32hex request key.
Retries require the original content/instructions/base/ordered file list, return the
original immutable attempt and never restore it as current, including after close
or restart. Changed files or legacy/file command mode mismatch409. Existing text-only
commands and12-field Submission snapshots stay unchanged. Legacy attempts return an
explicit empty file list. New resubmission never inherits files implicitly.

## Disclosure and recovery

Only author/current OWNER may list exact attempt files or mint a60second download
URL; generic Group members cannot. Draft upload operations require current OWNER or
MEMBER, OPEN Assignment and nonarchived Study. Closed/retired parents retain old
authorized downloads. Download revalidates the saved metadata against Media and
always uses the sealed object version. An issued URL is a bearer capability until
expiry and cannot be revoked retroactively; previously downloaded bytes cannot be
recalled. Do not render files inline. MinIO serves download content as attachment and
application/octet-stream. Frontend validates HTTPS URLs against Gateway media-config;
cookies never accompany object requests. The UI never renders the signed storage URL
as a navigable link. The explicit download button fetches with credentials omitted,
checks exact size and SHA256 while bounding memory to the declared10MiB file limit,
and saves an application/octet-stream Blob. Object URLs are revoked promptly.
Expired download preparations disappear from the UI.

Unknown upload outcomes keep the same allocation key and selected file in memory;
retry safely resumes existing pending/completed allocation. Unknown submit outcomes
retain exact intent for explicit retry, or explicit refresh before a fresh intent.
Inputs/selections/URLs clear on hide/unmount/authority loss; no localStorage/sessionStorage.
No background retry. Unsubmitted allocations may remain after leaving the page;
reclamation/orphan cleanup remains separate, with the existing storage limitations.

## APIs, events and operation

OpenAPI: submission-files.yaml and domain-media-files.yaml. Exact fields, duplicate/
trailing rejection, UUIDv7, bounded JSON, same-origin commands, singleton secure
session cookie, no-store, privacy404 and denied gateway internal routes apply.
Metrics: brain.collaboration.submission.files and brain.media.domain.files, fixed
operation labels only. No file names, answers, credentials or signed URLs in logs.

The new self-contained Protobuf is collaboration_submission_files_v1.proto,
Registry collaboration/submission-files-events/1, SHA256 a30fdd62f549bf42847a611d678f8fe61fd1773b2e7a9dbf69756636b473451a,
FULL validity/FULL_TRANSITIVE compatibility. SubmissionFilesAttached contains the
ordered immutable metadata with AUTHOR_AND_OWNER audience, standard envelope and
HTTP trace. Topic collaboration.submission-files.v1: Connect sole literal writer,
Group validator read-only. Consumers must deduplicate eventId and enforce current
audience. Existing MediaUploadRequested/MediaUploaded remain unchanged PRIVATE file
metadata facts; Group Media IDs confer no browser access. No new read projection.
CDC can read only each owner's Outbox, cannot SELECT new canonical tables; no
connector offset/publication/slot reset. No new dependencies or datasets.

Provision-SubmissionFiles.py creates only the dedicated local credential, preserving
it on repeat. Publish with Run-SubmissionFilesContractValidation.py and upgrade the
existing Connect Helm release, build/import Media/Collaboration/Gateway on both nodes,
roll out normally. Validate Media/storage/Kafka, Collaboration/group-Kafka, Gateway,
browser --submission-files with existing submission/grading regressions. Evidence is
recorded in submission-files-validation.txt and .local/submission-files-validation.json
only after actual checks. Central telemetry platform and file scanning remain separate.
