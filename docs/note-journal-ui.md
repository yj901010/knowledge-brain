# Daily Note and Journal UI

Daily Note save/change/archive events now also appear in the [private Activity timeline](note-activity.md). The Content Journal remains the canonical date-based view.

Personal Graph now includes a Daily Note / Journal card using the existing Content
canonical Daily Note APIs. Enter an explicit IANA timezone (for example Asia/Seoul
or UTC). Today is calculated by the server; reading today never creates a Note.
Opening an existing today record uses its immutable Note ID without changing its
original timezone. Opening/creating a selected date reuses the existing Note and
preserves its title, Markdown, version and associated content.

Journal lists active Daily Notes newest date first, with inclusive optional date
bounds, 20 rows per page and an exclusive date cursor. Loaded counts describe only
the displayed records. Ordinary Notes and other Activity events are not included.
Rows open the canonical Note editor, tags/properties and link inspector even when
the Note is outside the graph projection window. Saving the editor refreshes titles.

Creation timezone is immutable. A different timezone for an existing date or a
retired date returns a conflict; the UI does not overwrite, recreate or move it.
Private state is memory-only, resets on Space/hide/logout, and ignores stale or
aborted responses. Failed Journal reads clear rows; authentication or permission
denial clears the entire private view. Date validation does not infer a timezone.

No API, event, schema, dependency or service ownership changes. Content continues
to own date bindings and Note bodies; a future cross-domain Activity timeline is
separate. Build/import Gateway on both nodes, then use Install-LocalGraphJournal.py
with the existing Helm executable. Run Run-GraphWebValidation.py --public.
Evidence: note-journal-ui-validation.txt.
