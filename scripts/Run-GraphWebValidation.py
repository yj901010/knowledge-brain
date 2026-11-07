"""Confined local Playwright Job; public CA trust without TLS bypass."""
import base64,json,shutil,subprocess,time,sys,re
sys.stdout.reconfigure(encoding="utf-8")
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 r=subprocess.run(k+args,input=body,text=True,encoding="utf-8",capture_output=True)
 if r.returncode:raise RuntimeError(r.stderr)
 return r.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
name='graph-web-validation-'+str(time.time_ns())
files={'package.json':root/'apps/graph-web/package.json','package-lock.json':root/'apps/graph-web/package-lock.json','playwright.config.ts':root/'apps/graph-web/playwright.config.ts','graph.spec.ts':root/'apps/graph-web/tests/graph.spec.ts','editor.spec.ts':root/'apps/graph-web/tests/editor.spec.ts','search.spec.ts':root/'apps/graph-web/tests/search.spec.ts','links.spec.ts':root/'apps/graph-web/tests/links.spec.ts','metadata.spec.ts':root/'apps/graph-web/tests/metadata.spec.ts','journal.spec.ts':root/'apps/graph-web/tests/journal.spec.ts','attachments.spec.ts':root/'apps/graph-web/tests/attachments.spec.ts','library.spec.ts':root/'apps/graph-web/tests/library.spec.ts','markdown.spec.ts':root/'apps/graph-web/tests/markdown.spec.ts','retirement.spec.ts':root/'apps/graph-web/tests/retirement.spec.ts','concepts.spec.ts':root/'apps/graph-web/tests/concepts.spec.ts','concept-editor.spec.ts':root/'apps/graph-web/tests/concept-editor.spec.ts','concept-retirement.spec.ts':root/'apps/graph-web/tests/concept-retirement.spec.ts','concept-library.spec.ts':root/'apps/graph-web/tests/concept-library.spec.ts'}
files['property-filter.spec.ts']=root/'apps/graph-web/tests/property-filter.spec.ts'
files['property-range.spec.ts']=root/'apps/graph-web/tests/property-range.spec.ts'
files['property-tag.spec.ts']=root/'apps/graph-web/tests/property-tag.spec.ts'
files['metadata-name.spec.ts']=root/'apps/graph-web/tests/metadata-name.spec.ts'
files['property-notes.spec.ts']=root/'apps/graph-web/tests/property-notes.spec.ts'
files['tag-notes.spec.ts']=root/'apps/graph-web/tests/tag-notes.spec.ts'
files['retired-notes.spec.ts']=root/'apps/graph-web/tests/retired-notes.spec.ts'
files['note-restore.spec.ts']=root/'apps/graph-web/tests/note-restore.spec.ts'
files['concept-notes.spec.ts']=root/'apps/graph-web/tests/concept-notes.spec.ts'
files['concept-aliases.spec.ts']=root/'apps/graph-web/tests/concept-aliases.spec.ts'
files['concept-claims.spec.ts']=root/'apps/graph-web/tests/concept-claims.spec.ts'
files['personal-sources.spec.ts']=root/'apps/graph-web/tests/personal-sources.spec.ts'
files['claim-citations.spec.ts']=root/'apps/graph-web/tests/claim-citations.spec.ts'
files['external-mappings.spec.ts']=root/'apps/graph-web/tests/external-mappings.spec.ts'
files['claim-activity.spec.ts']=root/'apps/graph-web/tests/claim-activity.spec.ts'
files['knowledge-comparison.spec.ts']=root/'apps/graph-web/tests/knowledge-comparison.spec.ts'
files['claim-comparison.spec.ts']=root/'apps/graph-web/tests/claim-comparison.spec.ts'
files['retired-claims.spec.ts']=root/'apps/graph-web/tests/retired-claims.spec.ts'
files['retired-concepts.spec.ts']=root/'apps/graph-web/tests/retired-concepts.spec.ts'
files['retired-sources.spec.ts']=root/'apps/graph-web/tests/retired-sources.spec.ts'
files['retired-citations.spec.ts']=root/'apps/graph-web/tests/retired-citations.spec.ts'
files['retired-concept-details.spec.ts']=root/'apps/graph-web/tests/retired-concept-details.spec.ts'
files['retired-qualifiers.spec.ts']=root/'apps/graph-web/tests/retired-qualifiers.spec.ts'
files['typed-claims.spec.ts']=root/'apps/graph-web/tests/typed-claims.spec.ts'
files['claim-qualifiers.spec.ts']=root/'apps/graph-web/tests/claim-qualifiers.spec.ts'
files['source-citations.spec.ts']=root/'apps/graph-web/tests/source-citations.spec.ts'
files['claim-usages.spec.ts']=root/'apps/graph-web/tests/claim-usages.spec.ts'
files['claim-evidence.spec.ts']=root/'apps/graph-web/tests/claim-evidence.spec.ts'
files['evidence-comparison.spec.ts']=root/'apps/graph-web/tests/evidence-comparison.spec.ts'
files['brain-network.spec.ts']=root/'apps/graph-web/tests/brain-network.spec.ts'
files['roadmaps.spec.ts']=root/'apps/graph-web/tests/roadmaps.spec.ts'
files['learning-study.spec.ts']=root/'apps/graph-web/tests/learning-study.spec.ts'
files['learning-evidence.spec.ts']=root/'apps/graph-web/tests/learning-evidence.spec.ts'
files['concept-learning-evidence.spec.ts']=root/'apps/graph-web/tests/concept-learning-evidence.spec.ts'
files['media-learning-evidence.spec.ts']=root/'apps/graph-web/tests/media-learning-evidence.spec.ts'
files['evidence-sessions.spec.ts']=root/'apps/graph-web/tests/evidence-sessions.spec.ts'
files['activity-timeline.spec.ts']=root/'apps/graph-web/tests/activity-timeline.spec.ts'
files['goals.spec.ts']=root/'apps/graph-web/tests/goals.spec.ts'
files['tasks.spec.ts']=root/'apps/graph-web/tests/tasks.spec.ts'
files['schedules.spec.ts']=root/'apps/graph-web/tests/schedules.spec.ts'
files['planning-activity.spec.ts']=root/'apps/graph-web/tests/planning-activity.spec.ts'
files['recurrences.spec.ts']=root/'apps/graph-web/tests/recurrences.spec.ts'
files['reminders.spec.ts']=root/'apps/graph-web/tests/reminders.spec.ts'
files['notifications.spec.ts']=root/'apps/graph-web/tests/notifications.spec.ts'
files['bookmarks.spec.ts']=root/'apps/graph-web/tests/bookmarks.spec.ts'
files['notification-preferences.spec.ts']=root/'apps/graph-web/tests/notification-preferences.spec.ts'
files['questions.spec.ts']=root/'apps/graph-web/tests/questions.spec.ts'
files['decisions.spec.ts']=root/'apps/graph-web/tests/decisions.spec.ts'
files['record-concepts.spec.ts']=root/'apps/graph-web/tests/record-concepts.spec.ts'
files['record-network.spec.ts']=root/'apps/graph-web/tests/record-network.spec.ts'
if '--note-graph-readers-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','library.spec.ts','markdown.spec.ts','editor.spec.ts','attachments.spec.ts')}
if '--record-network-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','record-network.spec.ts')}
if '--record-concepts-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','record-concepts.spec.ts')}
if '--decisions-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','decisions.spec.ts')}
if '--questions-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','questions.spec.ts')}
if '--notification-preferences-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','notification-preferences.spec.ts')}
if '--bookmarks-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','bookmarks.spec.ts')}
if '--notifications-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','notifications.spec.ts')}
if '--reminders-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','reminders.spec.ts')}
if '--recurrences-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','recurrences.spec.ts')}
files['record-activity.spec.ts']=root/'apps/graph-web/tests/record-activity.spec.ts'
files['attachment-activity.spec.ts']=root/'apps/graph-web/tests/attachment-activity.spec.ts'
files['link-activity.spec.ts']=root/'apps/graph-web/tests/link-activity.spec.ts'
files['metadata-activity.spec.ts']=root/'apps/graph-web/tests/metadata-activity.spec.ts'
files['note-concept-activity.spec.ts']=root/'apps/graph-web/tests/note-concept-activity.spec.ts'
files['note-activity.spec.ts']=root/'apps/graph-web/tests/note-activity.spec.ts'
if '--attachment-activity-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','attachment-activity.spec.ts')}
if '--link-activity-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','link-activity.spec.ts')}
if '--metadata-activity-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','metadata-activity.spec.ts')}
if '--note-concept-activity-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','note-concept-activity.spec.ts')}
if '--note-activity-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','note-activity.spec.ts')}
if '--record-activity-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','record-activity.spec.ts')}
if '--planning-activity-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','planning-activity.spec.ts')}
if '--schedules-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','schedules.spec.ts')}
if '--tasks-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','tasks.spec.ts')}
if '--goals-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','goals.spec.ts')}
if '--activity-timeline-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','activity-timeline.spec.ts')}
if '--evidence-sessions-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','evidence-sessions.spec.ts')}
if '--media-learning-evidence-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','media-learning-evidence.spec.ts')}
if '--concept-learning-evidence-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','concept-learning-evidence.spec.ts')}
if '--learning-evidence-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','learning-evidence.spec.ts')}
if '--learning-study-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','learning-study.spec.ts')}
if '--roadmaps-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','roadmaps.spec.ts')}
if '--network-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','brain-network.spec.ts')}
if '--evidence-comparison-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','evidence-comparison.spec.ts')}
if '--claim-evidence-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','claim-evidence.spec.ts')}
if '--claim-usages-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','claim-usages.spec.ts')}
if '--source-citations-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','source-citations.spec.ts')}
if '--citations-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','claim-citations.spec.ts')}
if '--sources-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','personal-sources.spec.ts')}
if '--concept-claims-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','concept-claims.spec.ts')}
if '--concept-aliases-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','concept-aliases.spec.ts')}
if '--concept-notes-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','concept-notes.spec.ts')}
if '--metadata-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','metadata.spec.ts')}
if '--attachments-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','attachments.spec.ts')}
if '--library-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','library.spec.ts')}
if '--markdown-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','markdown.spec.ts')}
if '--retirement-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','retirement.spec.ts')}
if '--concepts-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','concepts.spec.ts')}
if '--concept-editor-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','concept-editor.spec.ts')}
if '--concept-retirement-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','concept-retirement.spec.ts')}
if '--concept-library-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','concept-library.spec.ts')}
files['public-roadmaps.spec.ts']=root/'apps/graph-web/tests/public-roadmaps.spec.ts'
if '--public-roadmaps-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','public-roadmaps.spec.ts')}
files['roadmap-adoptions.spec.ts']=root/'apps/graph-web/tests/roadmap-adoptions.spec.ts'
files['roadmap-ratings.spec.ts']=root/'apps/graph-web/tests/roadmap-ratings.spec.ts'
if '--roadmap-ratings-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','roadmap-ratings.spec.ts')}
if '--roadmap-adoptions-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','roadmap-adoptions.spec.ts')}
if '--public-only' in sys.argv:
 if '--public' not in sys.argv:raise ValueError('Public-only validation requires the public workflow')
 files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts')}
outage='--login-outage' in sys.argv
public_workflow='--public' in sys.argv or '--public-workbench' in sys.argv or '--public-discovery' in sys.argv or '--public-curation' in sys.argv
groups='--group-workspace' in sys.argv or '--group-my-feedback' in sys.argv or '--group-my-resubmissions' in sys.argv or '--group-my-submissions' in sys.argv or '--group-my-assignments' in sys.argv or '--group-review-queue' in sys.argv or '--group-grading-queue' in sys.argv or '--study-review-queue' in sys.argv or '--study-grading-queue' in sys.argv or '--course-review-queue' in sys.argv or '--course-grading-queue' in sys.argv or '--course-my-feedback' in sys.argv or '--course-my-resubmissions' in sys.argv or '--course-my-submissions' in sys.argv or '--course-my-assignments' in sys.argv or '--study-my-assignments' in sys.argv or '--study-my-feedback' in sys.argv or '--study-my-resubmissions' in sys.argv or '--study-my-submissions' in sys.argv or '--submission-grading-queue' in sys.argv or '--review-lifecycle-notifications' in sys.argv or '--group-notifications' in sys.argv or '--groups' in sys.argv or '--group-membership' in sys.argv or '--group-roadmaps' in sys.argv or '--group-studies' in sys.argv or '--study-roadmap' in sys.argv or '--group-assignments' in sys.argv or '--submissions' in sys.argv or '--discussions' in sys.argv or '--courses' in sys.argv or '--enrollments' in sys.argv or '--course-progress' in sys.argv or '--owner-summary' in sys.argv or '--submission-feedback' in sys.argv or '--resubmission-requests' in sys.argv or '--submission-instructions' in sys.argv or '--feedback-retractions' in sys.argv or '--submission-review-queue' in sys.argv or '--submission-grades' in sys.argv or '--grade-retractions' in sys.argv or '--assignment-rubrics' in sys.argv or '--rubric-grading' in sys.argv or '--submission-files' in sys.argv
if groups:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts')}
if groups:files['groups.spec.ts']=root/'apps/graph-web/tests/groups.spec.ts'
if '--group-membership' in sys.argv:files['group-membership.spec.ts']=root/'apps/graph-web/tests/group-membership.spec.ts'
if '--group-roadmaps' in sys.argv:files['group-roadmaps.spec.ts']=root/'apps/graph-web/tests/group-roadmaps.spec.ts'
if '--group-studies' in sys.argv:files['group-studies.spec.ts']=root/'apps/graph-web/tests/group-studies.spec.ts'
if '--study-roadmap' in sys.argv:files['study-roadmap.spec.ts']=root/'apps/graph-web/tests/study-roadmap.spec.ts'
if '--group-assignments' in sys.argv:files['group-assignments.spec.ts']=root/'apps/graph-web/tests/group-assignments.spec.ts'
if '--assignment-rubrics' in sys.argv:files['assignment-rubrics.spec.ts']=root/'apps/graph-web/tests/assignment-rubrics.spec.ts'
if '--group-my-feedback' in sys.argv:files['group-my-feedback.spec.ts']=root/'apps/graph-web/tests/group-my-feedback.spec.ts'
if '--group-my-resubmissions' in sys.argv:files['group-my-resubmissions.spec.ts']=root/'apps/graph-web/tests/group-my-resubmissions.spec.ts'
if '--group-my-submissions' in sys.argv:files['group-my-submissions.spec.ts']=root/'apps/graph-web/tests/group-my-submissions.spec.ts'
if '--group-my-assignments' in sys.argv:files['group-my-assignments.spec.ts']=root/'apps/graph-web/tests/group-my-assignments.spec.ts'
if '--group-review-queue' in sys.argv:files['group-review-queue.spec.ts']=root/'apps/graph-web/tests/group-review-queue.spec.ts'
if '--group-grading-queue' in sys.argv:files['group-grading-queue.spec.ts']=root/'apps/graph-web/tests/group-grading-queue.spec.ts'
if '--study-review-queue' in sys.argv:files['study-review-queue.spec.ts']=root/'apps/graph-web/tests/study-review-queue.spec.ts'
if '--study-grading-queue' in sys.argv:files['study-grading-queue.spec.ts']=root/'apps/graph-web/tests/study-grading-queue.spec.ts'
if '--course-review-queue' in sys.argv:files['course-review-queue.spec.ts']=root/'apps/graph-web/tests/course-review-queue.spec.ts'
if '--course-grading-queue' in sys.argv:files['course-grading-queue.spec.ts']=root/'apps/graph-web/tests/course-grading-queue.spec.ts'
if '--course-my-feedback' in sys.argv:files['course-my-feedback.spec.ts']=root/'apps/graph-web/tests/course-my-feedback.spec.ts'
if '--course-my-resubmissions' in sys.argv:files['course-my-resubmissions.spec.ts']=root/'apps/graph-web/tests/course-my-resubmissions.spec.ts'
if '--course-my-submissions' in sys.argv:files['course-my-submissions.spec.ts']=root/'apps/graph-web/tests/course-my-submissions.spec.ts'
if '--course-my-assignments' in sys.argv:files['course-my-assignments.spec.ts']=root/'apps/graph-web/tests/course-my-assignments.spec.ts'
if '--study-my-assignments' in sys.argv:files['study-my-assignments.spec.ts']=root/'apps/graph-web/tests/study-my-assignments.spec.ts'
if '--study-my-feedback' in sys.argv:files['study-my-feedback.spec.ts']=root/'apps/graph-web/tests/study-my-feedback.spec.ts'
if '--study-my-resubmissions' in sys.argv:files['study-my-resubmissions.spec.ts']=root/'apps/graph-web/tests/study-my-resubmissions.spec.ts'
if '--study-my-submissions' in sys.argv:files['study-my-submissions.spec.ts']=root/'apps/graph-web/tests/study-my-submissions.spec.ts'
if '--submission-grading-queue' in sys.argv:files['submission-grading-queue.spec.ts']=root/'apps/graph-web/tests/submission-grading-queue.spec.ts'
if '--review-lifecycle-notifications' in sys.argv:files['review-lifecycle-notifications.spec.ts']=root/'apps/graph-web/tests/review-lifecycle-notifications.spec.ts'
if '--group-notifications' in sys.argv:files['group-notifications.spec.ts']=root/'apps/graph-web/tests/group-notifications.spec.ts'
if '--group-notifications' in sys.argv:
 for notification_spec in ['notifications.spec.ts','notification-preferences.spec.ts']:files[notification_spec]=root/'apps/graph-web/tests'/notification_spec
if '--submission-files' in sys.argv:files['submission-files.spec.ts']=root/'apps/graph-web/tests/submission-files.spec.ts'
if '--submission-files' in sys.argv:files['attachments.spec.ts']=root/'apps/graph-web/tests/attachments.spec.ts'
if '--rubric-grading' in sys.argv:files['rubric-grading.spec.ts']=root/'apps/graph-web/tests/rubric-grading.spec.ts'
if '--grade-retractions' in sys.argv:files['grade-retractions.spec.ts']=root/'apps/graph-web/tests/grade-retractions.spec.ts'
if '--submission-grades' in sys.argv:files['submission-grades.spec.ts']=root/'apps/graph-web/tests/submission-grades.spec.ts'
if '--submission-review-queue' in sys.argv:files['submission-review-queue.spec.ts']=root/'apps/graph-web/tests/submission-review-queue.spec.ts'
if '--feedback-retractions' in sys.argv:files['feedback-retractions.spec.ts']=root/'apps/graph-web/tests/feedback-retractions.spec.ts'
if '--submission-instructions' in sys.argv:files['submission-instructions.spec.ts']=root/'apps/graph-web/tests/submission-instructions.spec.ts'
if '--resubmission-requests' in sys.argv:files['resubmission-requests.spec.ts']=root/'apps/graph-web/tests/resubmission-requests.spec.ts'
if '--submission-feedback' in sys.argv:files['submission-feedback.spec.ts']=root/'apps/graph-web/tests/submission-feedback.spec.ts'
if '--submissions' in sys.argv:files['submissions.spec.ts']=root/'apps/graph-web/tests/submissions.spec.ts'
if '--owner-summary' in sys.argv:files['owner-summary.spec.ts']=root/'apps/graph-web/tests/owner-summary.spec.ts'
if '--course-progress' in sys.argv:files['course-progress.spec.ts']=root/'apps/graph-web/tests/course-progress.spec.ts'
if '--enrollments' in sys.argv:files['course-enrollments.spec.ts']=root/'apps/graph-web/tests/course-enrollments.spec.ts'
if '--courses' in sys.argv:files['courses.spec.ts']=root/'apps/graph-web/tests/courses.spec.ts'
if '--discussions' in sys.argv:files['discussions.spec.ts']=root/'apps/graph-web/tests/discussions.spec.ts'
login='--unicode-contracts' in sys.argv or '--learning-guidance' in sys.argv or '--accessibility' in sys.argv or '--roadmap-workspace' in sys.argv or '--knowledge-workspace' in sys.argv or '--workspace-flow' in sys.argv or '--login' in sys.argv or outage or public_workflow or groups or '--github-public' in sys.argv or '--github-public-only' in sys.argv or '--github-regressions' in sys.argv or '--github-sync-history' in sys.argv or '--github-learning-bundle' in sys.argv or '--github-learning-functional' in sys.argv or '--github-work-bundle' in sys.argv or '--github-work-functional' in sys.argv or '--github-work-main' in sys.argv
if login and not public_workflow and not groups:files['login.spec.ts']=root/'apps/graph-web/tests/login.spec.ts'
if public_workflow:files['public.spec.ts']=root/'apps/graph-web/tests/public.spec.ts'
if '--qualifiers-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','claim-qualifiers.spec.ts')}
if '--external-mappings-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','external-mappings.spec.ts')}
if '--typed-claims-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','typed-claims.spec.ts')}
if '--claim-activity-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','claim-activity.spec.ts')}
if '--activity-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','activity-timeline.spec.ts','planning-activity.spec.ts','record-activity.spec.ts','note-activity.spec.ts','note-concept-activity.spec.ts','metadata-activity.spec.ts','link-activity.spec.ts','attachment-activity.spec.ts','claim-activity.spec.ts')}
if '--knowledge-comparison-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','knowledge-comparison.spec.ts')}
if '--knowledge-comparison-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','knowledge-comparison.spec.ts','concept-editor.spec.ts','concept-retirement.spec.ts','personal-sources.spec.ts','retired-concepts.spec.ts','retired-sources.spec.ts','retired-concept-details.spec.ts','claim-comparison.spec.ts','evidence-comparison.spec.ts')}
if '--claim-comparison-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','claim-comparison.spec.ts')}
if '--claim-comparison-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','claim-comparison.spec.ts','concept-claims.spec.ts','typed-claims.spec.ts','claim-activity.spec.ts','claim-citations.spec.ts','claim-qualifiers.spec.ts')}
if '--retired-claims-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','retired-claims.spec.ts')}
if '--retired-claims-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','retired-claims.spec.ts','claim-comparison.spec.ts','concept-claims.spec.ts','typed-claims.spec.ts','claim-activity.spec.ts','claim-citations.spec.ts','claim-qualifiers.spec.ts','concept-retirement.spec.ts')}
if '--retired-concepts-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','retired-concepts.spec.ts')}
if '--retired-concepts-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','retired-concepts.spec.ts','retired-claims.spec.ts','concept-library.spec.ts','concept-editor.spec.ts','concept-retirement.spec.ts','concepts.spec.ts','claim-comparison.spec.ts')}
if '--retired-sources-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','retired-sources.spec.ts')}
if '--retired-citations-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','retired-citations.spec.ts')}
if '--retired-concept-details-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','retired-concept-details.spec.ts')}
if '--retired-concept-details-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','retired-concept-details.spec.ts','concept-aliases.spec.ts','external-mappings.spec.ts','retired-concepts.spec.ts','retired-claims.spec.ts','retired-citations.spec.ts','retired-qualifiers.spec.ts')}
if '--retired-qualifiers-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','retired-qualifiers.spec.ts')}
if '--retired-qualifiers-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','retired-qualifiers.spec.ts','retired-citations.spec.ts','retired-claims.spec.ts','retired-concepts.spec.ts','claim-qualifiers.spec.ts','typed-claims.spec.ts','claim-comparison.spec.ts')}
if '--retired-citations-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','retired-citations.spec.ts','retired-claims.spec.ts','retired-concepts.spec.ts','retired-sources.spec.ts','claim-citations.spec.ts','source-citations.spec.ts','claim-evidence.spec.ts','evidence-comparison.spec.ts')}
if '--retired-sources-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','retired-sources.spec.ts','personal-sources.spec.ts','source-citations.spec.ts','claim-citations.spec.ts','claim-evidence.spec.ts','evidence-comparison.spec.ts','retired-concepts.spec.ts')}
if '--note-restore-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','note-restore.spec.ts')}
if '--note-restore-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','note-restore.spec.ts','editor.spec.ts','markdown.spec.ts','retirement.spec.ts','metadata.spec.ts','journal.spec.ts','links.spec.ts','attachments.spec.ts','concepts.spec.ts')}
if '--metadata-name-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','metadata-name.spec.ts')}
if '--metadata-name-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','metadata-name.spec.ts','property-tag.spec.ts','property-range.spec.ts','property-filter.spec.ts','property-notes.spec.ts','tag-notes.spec.ts','metadata.spec.ts','metadata-activity.spec.ts','library.spec.ts','retirement.spec.ts')}
if '--property-tag-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','property-tag.spec.ts')}
if '--property-tag-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','property-tag.spec.ts','property-range.spec.ts','property-filter.spec.ts','property-notes.spec.ts','tag-notes.spec.ts','metadata.spec.ts','metadata-activity.spec.ts','library.spec.ts','retirement.spec.ts')}
if '--property-range-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','property-range.spec.ts')}
if '--property-range-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','property-range.spec.ts','property-filter.spec.ts','property-notes.spec.ts','tag-notes.spec.ts','metadata.spec.ts','metadata-activity.spec.ts','library.spec.ts','retirement.spec.ts')}
if '--property-filter-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','property-filter.spec.ts')}
if '--property-filter-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','property-filter.spec.ts','property-notes.spec.ts','tag-notes.spec.ts','metadata.spec.ts','metadata-activity.spec.ts','library.spec.ts','retirement.spec.ts')}
if '--property-notes-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','property-notes.spec.ts')}
if '--property-notes-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','property-notes.spec.ts','tag-notes.spec.ts','metadata.spec.ts','metadata-activity.spec.ts','library.spec.ts','retirement.spec.ts','journal.spec.ts','retired-notes.spec.ts','note-restore.spec.ts')}
if '--tag-notes-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','tag-notes.spec.ts')}
if '--tag-notes-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','tag-notes.spec.ts','metadata.spec.ts','library.spec.ts','retirement.spec.ts','journal.spec.ts','retired-notes.spec.ts','note-restore.spec.ts')}
if '--retired-notes-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','retired-notes.spec.ts')}
if '--retired-notes-regressions' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','retired-notes.spec.ts','note-restore.spec.ts','editor.spec.ts','retirement.spec.ts','library.spec.ts','journal.spec.ts','markdown.spec.ts')}
bridge="const net=require('net');net.createServer(c=>{const s=net.connect(8080,'gateway-service.brain-apps.svc');c.pipe(s);s.pipe(c);c.on('error',()=>s.destroy());s.on('error',()=>c.destroy())}).listen(8443,'127.0.0.1')"
bridge+=";net.createServer(c=>{const s=net.connect(9000,'brain-minio.brain-data.svc');c.pipe(s);s.pipe(c);c.on('error',()=>s.destroy());s.on('error',()=>c.destroy())}).listen(9443,'127.0.0.1')"
if login:bridge+=";net.createServer(c=>{const s=net.connect(8443,'brain-login.brain-apps.svc');c.pipe(s);s.pipe(c);c.on('error',()=>s.destroy());s.on('error',()=>c.destroy())}).listen(8445,'127.0.0.1')"
shell='''apt-get update -qq && apt-get install -y -qq libnss3-tools >/dev/null || exit 1
mkdir -p /tmp/graph-home/.pki/nssdb /tmp/build/tests /tmp/graph-ui-results
export HOME=/tmp/graph-home
export NODE_EXTRA_CA_CERTS=/trust/ca.crt
certutil -N -d sql:$HOME/.pki/nssdb --empty-password
certutil -A -d sql:$HOME/.pki/nssdb -n brain-local -t "C,," -i /trust/ca.crt
cp /source/package*.json /source/playwright.config.ts /tmp/build/
cp /source/*.spec.ts /tmp/build/tests/
if [ -f /source/public-curation-pack.json ]; then mkdir -p /tmp/build/app; cp /source/public-curation-pack.json /tmp/build/app/; fi
node /source/bridge.js &
cd /tmp/build
npm ci --no-audit --no-fund >/dev/null || exit 1
npx playwright test
result=$?
if [ "${BRAIN_UI_SKIP_IMAGES:-0}" != "1" ]; then
for image in /tmp/graph-ui-results/*.png; do [ -f "$image" ] || continue; if [ "${BRAIN_UI_PLANNING_IMAGES_ONLY:-0}" = "1" ]; then case "$(basename "$image")" in planning-activity*.png) ;; *) continue ;; esac; fi; printf 'GRAPH_UI_IMAGE=%s;' "$(basename "$image")" && base64 -w0 "$image" && printf '\\n'; done
fi
printf 'GRAPH_UI_VALIDATION_EXIT_CODE=%s\n' "$result"
exit $result
'''
# Opt-in serial functional checks for the resource-limited local browser Pod; defaults and assertions stay unchanged.
if '--serial' in sys.argv:shell=shell.replace('npx playwright test\n','npx playwright test --workers=1\n')
if '--github-public' in sys.argv or '--github-public-only' in sys.argv:
 files['github-public.spec.ts']=root/'apps/graph-web/tests/github-public.spec.ts'
 if '--github-public-only' in sys.argv:files={name:path for name,path in files.items() if name in ('package.json','package-lock.json','playwright.config.ts','github-public.spec.ts')}
if '--github-regressions' in sys.argv:
 files={name:root/'apps/graph-web/tests'/name for name in ('github-public.spec.ts','group-my-feedback.spec.ts','activity-timeline.spec.ts','planning-activity.spec.ts','claim-activity.spec.ts')}
 files.update({name:root/'apps/graph-web'/name for name in ('package.json','package-lock.json','playwright.config.ts')})
if '--github-sync-history' in sys.argv:
 files={name:root/'apps/graph-web/tests'/name for name in ('github-sync-history.spec.ts','github-public.spec.ts','group-my-feedback.spec.ts','activity-timeline.spec.ts')}
 files.update({name:root/'apps/graph-web'/name for name in ('package.json','package-lock.json','playwright.config.ts')})
if '--github-learning-bundle' in sys.argv:
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files.update({n:root/'apps/graph-web/tests'/n for n in ['github-learning-bundle.spec.ts','github-sync-history.spec.ts','evidence-sessions.spec.ts','activity-timeline.spec.ts']});shell=shell.replace('npx playwright test','npx playwright test github-learning-bundle.spec.ts github-sync-history.spec.ts evidence-sessions.spec.ts activity-timeline.spec.ts --workers=1')

if '--github-learning-functional' in sys.argv:
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files['github-learning-bundle.spec.ts']=root/'apps/graph-web/tests/github-learning-bundle.spec.ts';shell=shell.replace('npx playwright test','npx playwright test github-learning-bundle.spec.ts --grep-invert Activity --workers=1')

if '--github-work-bundle' in sys.argv or '--github-work-functional' in sys.argv or '--github-work-main' in sys.argv:
 specs=['github-work-bundle.spec.ts'] if '--github-work-functional' in sys.argv or '--github-work-main' in sys.argv else ['github-work-bundle.spec.ts','github-learning-bundle.spec.ts','github-sync-history.spec.ts','evidence-sessions.spec.ts','activity-timeline.spec.ts']
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files.update({n:root/'apps/graph-web/tests'/n for n in specs});shell=shell.replace('npx playwright test','npx playwright test '+(' '.join(specs))+' --workers=1'+(' --grep-invert Activity' if '--github-work-functional' in sys.argv else ''))

if '--github-work-main' in sys.argv:shell=shell.replace('npx playwright test github-work-bundle.spec.ts --workers=1','npx playwright test github-work-bundle.spec.ts --workers=1 --grep \"^real public\"')

if '--public-workbench' in sys.argv:
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files['public-workbench.spec.ts']=root/'apps/graph-web/tests/public-workbench.spec.ts';shell=shell.replace('npx playwright test','npx playwright test public-workbench.spec.ts --workers=1')

if '--public-discovery' in sys.argv:
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files['public-discovery.spec.ts']=root/'apps/graph-web/tests/public-discovery.spec.ts';files['public-discovery-layout.spec.ts']=root/'apps/graph-web/tests/public-discovery-layout.spec.ts';shell=shell.replace('npx playwright test','npx playwright test public-discovery.spec.ts public-discovery-layout.spec.ts --workers=1')

if '--public-cache' in sys.argv:
 specs=['public-discovery-layout.spec.ts','search.spec.ts','graph.spec.ts']
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files.update({n:root/'apps/graph-web/tests'/n for n in specs});shell=shell.replace('npx playwright test','npx playwright test '+' '.join(specs)+' --workers=1')

if '--accessibility' in sys.argv:
 specs=['accessibility.spec.ts','public-tables.spec.ts']
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files.update({n:root/'apps/graph-web/tests'/n for n in specs});shell=shell.replace('npx playwright test','npx playwright test '+' '.join(specs)+' --workers=1')
 if '--accessibility-graph-only' in sys.argv:shell=shell.replace('npx playwright test accessibility.spec.ts public-tables.spec.ts --workers=1','npx playwright test accessibility.spec.ts --grep "loaded private graph" --workers=1')
 if '--accessibility-tables-only' in sys.argv:shell=shell.replace('npx playwright test accessibility.spec.ts public-tables.spec.ts --workers=1','npx playwright test public-tables.spec.ts --workers=1')

if '--graph-performance' in sys.argv:
 specs=['graph-performance.spec.ts']
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files.update({n:root/'apps/graph-web/tests'/n for n in specs});shell=shell.replace('npx playwright test','npx playwright test '+' '.join(specs)+' --workers=1')

if '--workspace-design' in sys.argv:
 specs=['workspace-design.spec.ts','graph.spec.ts','editor.spec.ts','search.spec.ts','brain-network.spec.ts','public-discovery-layout.spec.ts','workspace-roadmap.spec.ts','public-roadmaps.spec.ts']
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files.update({n:root/'apps/graph-web/tests'/n for n in specs});shell=shell.replace('npx playwright test','npx playwright test '+' '.join(specs)+' --workers=1')

if '--workspace-roadmap' in sys.argv:
 specs=['workspace-roadmap.spec.ts','public-roadmaps.spec.ts']
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files.update({n:root/'apps/graph-web/tests'/n for n in specs});shell=shell.replace('npx playwright test','npx playwright test '+' '.join(specs)+' --workers=1')

if '--workspace-flow' in sys.argv:
 specs=['workspace-flow.spec.ts','workspace-design.spec.ts','graph.spec.ts','editor.spec.ts','search.spec.ts','markdown.spec.ts','note-restore.spec.ts','brain-network.spec.ts','roadmaps.spec.ts','goals.spec.ts']
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files.update({n:root/'apps/graph-web/tests'/n for n in specs});shell=shell.replace('npx playwright test','npx playwright test '+' '.join(specs)+' --workers=1')

if '--group-workspace' in sys.argv:
 specs=['group-workspace.spec.ts','groups.spec.ts','group-membership.spec.ts','group-my-assignments.spec.ts','group-my-submissions.spec.ts','group-my-resubmissions.spec.ts','group-my-feedback.spec.ts','group-review-queue.spec.ts','group-grading-queue.spec.ts','rubric-grading.spec.ts','submission-files.spec.ts','group-notifications.spec.ts','course-progress.spec.ts','courses.spec.ts']
 if '--focused-only' in sys.argv:specs=['group-workspace.spec.ts']
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files.update({n:root/'apps/graph-web/tests'/n for n in specs});shell=shell.replace('npx playwright test','npx playwright test '+' '.join(specs)+' --workers=1')

if '--knowledge-workspace' in sys.argv:
 specs=['knowledge-workspace.spec.ts','public-discovery-layout.spec.ts','workspace-design.spec.ts']
 if '--focused-only' in sys.argv:specs=['knowledge-workspace.spec.ts']
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files.update({n:root/'apps/graph-web/tests'/n for n in specs});shell=shell.replace('npx playwright test','npx playwright test '+' '.join(specs)+' --workers=1')

if '--roadmap-workspace' in sys.argv:
 specs=['roadmap-workspace.spec.ts','public-roadmaps.spec.ts','workspace-roadmap.spec.ts','roadmap-ratings.spec.ts','roadmap-adoptions.spec.ts','roadmaps.spec.ts','workspace-design.spec.ts']
 if '--focused-only' in sys.argv:specs=['roadmap-workspace.spec.ts']
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files.update({n:root/'apps/graph-web/tests'/n for n in specs});shell=shell.replace('npx playwright test','npx playwright test '+' '.join(specs)+' --workers=1')

if '--learning-guidance' in sys.argv:
 specs=['learning-guidance.spec.ts','learning-study.spec.ts','roadmaps.spec.ts']
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files.update({n:root/'apps/graph-web/tests'/n for n in specs});shell=shell.replace('npx playwright test','npx playwright test '+' '.join(specs)+' --workers=1')

if '--unicode-contracts' in sys.argv:
 specs=['unicode-personal.spec.ts','unicode-public-group.spec.ts']
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files.update({n:root/'apps/graph-web/tests'/n for n in specs});shell=shell.replace('npx playwright test','npx playwright test '+' '.join(specs)+' --workers=1')

if '--public-curation' in sys.argv:
 specs=['public-curation.spec.ts']
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files.update({n:root/'apps/graph-web/tests'/n for n in specs});files['public-curation-pack.json']=root/'apps/graph-web/app/public-curation-pack.json';shell=shell.replace('npx playwright test','npx playwright test '+' '.join(specs)+' --workers=1')

if '--v1-personal-audit' in sys.argv:
 specs=['metadata.spec.ts','journal.spec.ts','attachments.spec.ts','property-filter.spec.ts','property-range.spec.ts','property-tag.spec.ts','library.spec.ts','bookmarks.spec.ts','questions.spec.ts','decisions.spec.ts','record-concepts.spec.ts','record-network.spec.ts','source-citations.spec.ts','typed-claims.spec.ts','claim-qualifiers.spec.ts','concept-notes.spec.ts']
 files={n:root/'apps/graph-web'/n for n in ['package.json','package-lock.json','playwright.config.ts']};files.update({n:root/'apps/graph-web/tests'/n for n in specs});shell=shell.replace('npx playwright test','npx playwright test '+' '.join(specs)+' --workers=1')

data={n:p.read_text(encoding='utf-8') for n,p in files.items()};data.update({'bridge.js':bridge,'run.sh':shell})
run(['apply','--server-side','-f','-'],json.dumps({'apiVersion':'v1','kind':'ConfigMap','metadata':{'name':name,'namespace':'brain-data'},'data':data}))
job={'apiVersion':'batch/v1','kind':'Job','metadata':{'name':name,'namespace':'brain-data'},'spec':{'backoffLimit':0,'activeDeadlineSeconds':max(600,min(1800,120+45*sum(filename.endswith('.spec.ts') for filename in files))),'ttlSecondsAfterFinished':86400,'template':{'metadata':{'labels':{'brain-guest-api-validation':'true'}},'spec':{'restartPolicy':'Never','automountServiceAccountToken':False,'containers':[{'name':'verify','image':'mcr.microsoft.com/playwright:v1.64.0-noble','command':['bash','/source/run.sh'],'securityContext':{'allowPrivilegeEscalation':False},'resources':{'requests':{'cpu':'100m','memory':'512Mi'},'limits':{'cpu':'1500m','memory':'1536Mi'}},'volumeMounts':[{'name':'source','mountPath':'/source','readOnly':True},{'name':'trust','mountPath':'/trust','readOnly':True},{'name':'shm','mountPath':'/dev/shm'}]}],'volumes':[{'name':'source','configMap':{'name':name}},{'name':'trust','secret':{'secretName':'brain-guest-api-trust'}},{'name':'shm','emptyDir':{'medium':'Memory','sizeLimit':'256Mi'}}]}}}}
job['metadata']['annotations']={'brain.knowledge/gateway-image':json.loads(run(['get','deployment','gateway-service','-n','brain-apps','-o','json']))['spec']['template']['spec']['containers'][0]['image'],'brain.knowledge/kube-system-uid':json.loads(run(['get','namespace','kube-system','-o','json']))['metadata']['uid']}
if '--public-cache' in sys.argv:job['metadata']['annotations']['brain.knowledge/query-image']=json.loads(run(['get','deployment','brain-query-service','-n','brain-apps','-o','json']))['spec']['template']['spec']['containers'][0]['image']
if '--github-learning-bundle' in sys.argv or '--github-work-bundle' in sys.argv or '--public-workbench' in sys.argv or '--public-discovery' in sys.argv or '--workspace-design' in sys.argv or '--workspace-flow' in sys.argv:job['spec']['activeDeadlineSeconds']=1500
if '--accessibility' in sys.argv:job['spec']['activeDeadlineSeconds']=1500
if '--unicode-contracts' in sys.argv or '--learning-guidance' in sys.argv or '--public-curation' in sys.argv:job['spec']['activeDeadlineSeconds']=1800
if '--group-workspace' in sys.argv or '--knowledge-workspace' in sys.argv or '--roadmap-workspace' in sys.argv:job['spec']['activeDeadlineSeconds']=1800
if '--github-work-functional' in sys.argv or '--github-work-main' in sys.argv:job['spec']['template']['spec']['containers'][0]['env']=[dict(name='BRAIN_TEST_GITHUB_WORK_ACTIVITY',value='false')]
if outage:job['spec']['template']['spec']['containers'][0]['env']=[{'name':'BRAIN_TEST_LOGIN_OUTAGE','value':'true'}]
if '--no-images' in sys.argv:job['spec']['template']['spec']['containers'][0].setdefault('env',[]).append({'name':'BRAIN_UI_SKIP_IMAGES','value':'1'})
if '--planning-activity-images' in sys.argv:job['spec']['template']['spec']['containers'][0].setdefault('env',[]).append({'name':'BRAIN_UI_PLANNING_IMAGES_ONLY','value':'1'})
run(['apply','-f','-'],json.dumps(job))
uid=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))['metadata']['uid']
run(['patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':uid}]}})])
print('JOB='+name,flush=True)
original=None;paused=False;restored=False
reviewer=None;reviewer_revoked=False
capabilities={};expert_revoked=False
public_rebuild=None;public_rebuild_log=None;public_rebuild_job=None;public_snapshot_sent=False;public_resumed=False
def reviewer_role(principal,revoke=False):
 command=[sys.executable,str(root/'scripts/Set-LocalPublicReviewer.py'),'--principal-id',principal,'--domain','DEVELOPMENT']+(['--revoke'] if revoke else [])
 result=subprocess.run(command,text=True,encoding='utf-8',capture_output=True)
 if result.returncode:raise RuntimeError('Scoped test reviewer operation failed')
 print(result.stdout.strip(),flush=True)
def capability(principal,kind,revoke=False):
 command=[sys.executable,str(root/'scripts/Set-LocalPublicCapability.py'),'--principal-id',principal,'--domain','DEVELOPMENT','--capability',kind,'--provenance','Isolated automated validation authority; not a professional credential']+(['--revoke'] if revoke else [])
 result=subprocess.run(command,text=True,encoding='utf-8',capture_output=True)
 if result.returncode:raise RuntimeError('Scoped test capability operation failed')
 print(result.stdout.strip(),flush=True)
try:
 for _ in range(max(600,job['spec']['activeDeadlineSeconds'])):
  state=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))
  if state.get('status',{}).get('succeeded') or state.get('status',{}).get('failed'):break
  if public_workflow and _%2==0:
   result=subprocess.run(k+['logs','job/'+name,'-n','brain-data'],text=True,encoding='utf-8',capture_output=True)
   marker=re.search(r'(?:PUBLIC_CURATION_REVIEWER_READY|PUBLIC_REVIEWER_READY)=([0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})',result.stdout)
   if marker and reviewer is None:reviewer=marker.group(1);reviewer_role(reviewer)
   if '--public-discovery' in sys.argv:
    if 'PUBLIC_REBUILD_REQUEST_READY' in result.stdout and public_rebuild is None:
     public_rebuild_log=open(root/'.local/public-discovery-browser-rebuild.log','w',encoding='utf-8');public_rebuild=subprocess.Popen([sys.executable,str(root/'scripts/Rebuild-LocalPublicDiscovery.py'),'--pause'],stdout=public_rebuild_log,stderr=subprocess.STDOUT)
    if public_rebuild is not None:
     public_rebuild_log.flush();progress=(root/'.local/public-discovery-browser-rebuild.log').read_text(encoding='utf-8');m=re.search(r'PUBLIC_REBUILD_JOB=([a-z0-9-]+)',progress)
     if m:public_rebuild_job=m.group(1)
     if public_rebuild_job:
      public_log=subprocess.run(k+['logs','job/'+public_rebuild_job,'-n','brain-data'],text=True,encoding='utf-8',capture_output=True);detail=public_log.stdout if public_log.returncode==0 else ''
      if 'PUBLIC_REBUILD_SNAPSHOT_READY' in detail and not public_snapshot_sent:
       pod=json.loads(run(['get','pods','-n','brain-data','-l','job-name='+name,'-o','json']))['items'][0]['metadata']['name'];run(['exec',pod,'-n','brain-data','--','touch','/tmp/public-rebuild-snapshot.ready']);public_snapshot_sent=True;print('PASS public rebuild scan complete; browser will write a canonical tail update',flush=True)
      if 'PUBLIC_REBUILD_RESUME_READY' in result.stdout and not public_resumed:
       pod=json.loads(run(['get','pods','-n','brain-data','-l','job-name='+public_rebuild_job,'-o','json']))['items'][0]['metadata']['name'];run(['exec',pod,'-n','brain-data','--','touch','/tmp/rebuild.resume']);public_resumed=True
     if public_rebuild.poll() not in (None,0):raise RuntimeError('Public rebuild failed; inspect retained operator proof')
   if reviewer and '--public-workbench' in sys.argv:
    if 'PUBLIC_EXPERT_GRANT_READY='+reviewer in result.stdout and 'CLAIM_EXPERT_VERIFY' not in capabilities:capability(reviewer,'CLAIM_EXPERT_VERIFY');capabilities['CLAIM_EXPERT_VERIFY']=True
    if 'PUBLIC_EXPERT_REVOKE_READY='+reviewer in result.stdout and not expert_revoked:capability(reviewer,'CLAIM_EXPERT_VERIFY',True);capabilities['CLAIM_EXPERT_VERIFY']=False;expert_revoked=True
    if 'PUBLIC_OFFICIAL_GRANT_READY='+reviewer in result.stdout and 'MAPPING_OFFICIAL_REVIEW' not in capabilities:capability(reviewer,'MAPPING_OFFICIAL_REVIEW');capabilities['MAPPING_OFFICIAL_REVIEW']=True
   if reviewer and 'PUBLIC_REVIEWER_REVOKE_READY='+reviewer in result.stdout and not reviewer_revoked:reviewer_role(reviewer,True);reviewer_revoked=True
  if outage and _%2==0:
   r=subprocess.run(k+['logs','job/'+name,'-n','brain-data'],text=True,encoding='utf-8',capture_output=True)
   if 'LOGIN_KEYCLOAK_OUTAGE_READY' in r.stdout and not paused:
    original=json.loads(run(['get','deployment','brain-keycloak','-n','brain-data','-o','json']))['spec']['replicas']
    if original<1:raise RuntimeError('Keycloak must be running before fault validation')
    run(['scale','deployment/brain-keycloak','-n','brain-data','--replicas=0']);paused=True;print('PASS scoped Keycloak outage started',flush=True)
   if 'LOGIN_KEYCLOAK_OUTAGE_OBSERVED' in r.stdout and paused and not restored:
    run(['scale','deployment/brain-keycloak','-n','brain-data','--replicas='+str(original)]);restored=True;print('PASS Keycloak original replica count restored',flush=True)
  time.sleep(1)
finally:
 if public_rebuild is not None:
  if public_rebuild_job and not public_resumed:
   pods=json.loads(run(['get','pods','-n','brain-data','-l','job-name='+public_rebuild_job,'-o','json']))['items']
   if pods and pods[0].get('status',{}).get('phase')=='Running':run(['exec',pods[0]['metadata']['name'],'-n','brain-data','--','touch','/tmp/rebuild.resume'])
  public_rebuild.wait(timeout=180);public_rebuild_log.close()
 if reviewer:
  for kind,active in capabilities.items():
   if active:capability(reviewer,kind,True)
 if reviewer and not reviewer_revoked:reviewer_role(reviewer,True)
 if paused:
  if not restored:run(['scale','deployment/brain-keycloak','-n','brain-data','--replicas='+str(original)])
  run(['rollout','status','deployment/brain-keycloak','-n','brain-data','--timeout=60s'])
logs=run(['logs','job/'+name,'-n','brain-data']);output=root/'.local/graph-ui-results';output.mkdir(exist_ok=True)
clean=[]
for line in logs.splitlines():
 if line.startswith('GRAPH_UI_IMAGE='):
  filename,value=line[len('GRAPH_UI_IMAGE='):].split(';',1)
  if filename in {'learning-guidance-desktop.png','learning-guidance-mobile.png','public-curation-desktop.png','public-curation-mobile.png'}:(output/filename).write_bytes(base64.b64decode(value))
  if filename in ['public-tables-desktop.png','public-tables-mobile.png','roadmap-workspace-discovery.png','roadmap-workspace-editor-mobile.png','roadmap-workspace-adoption-mobile.png','knowledge-workspace-discovery.png','knowledge-workspace-author-mobile.png','knowledge-workspace-workbench-mobile.png','group-workspace-owner.png','group-workspace-owner-mobile.png','group-workspace-viewer-mobile.png','group-workspace-grading-mobile.png','workspace-flow-planning.png','workspace-flow-planning-mobile.png','workspace-flow-compose.png','workspace-flow-compose-mobile.png','workspace-graph-light.png','workspace-roadmap-mobile.png','workspace-desktop.png','workspace-mobile.png','workspace-graph-desktop.png','workspace-graph-mobile.png','workspace-dense-graph.png','workspace-public-desktop.png','workspace-public-mobile.png','public-discovery-mobile.png','public-discovery-desktop.png','public-workbench-desktop.png','public-workbench-mobile.png','github-work-mobile.png','github-sync-history-mobile.png','github-mobile.png','desktop.png','mobile.png','login.png','login-mobile.png','public.png','public-mobile.png','public-roadmaps-mobile.png','roadmap-ratings-mobile.png','references.png','references-mobile.png','editor.png','editor-mobile.png','search.png','search-mobile.png','links.png','links-mobile.png','metadata.png','metadata-mobile.png','journal.png','journal-mobile.png','attachments.png','attachments-mobile.png','attachments-upload.png','attachments-upload-mobile.png','library.png','library-mobile.png','markdown.png','markdown-mobile.png','retirement.png','retirement-mobile.png','concepts.png','concepts-mobile.png','concept-editor.png','concept-editor-mobile.png','concept-retirement.png','concept-retirement-mobile.png','concept-library.png','concept-library-mobile.png','concept-notes.png','concept-notes-mobile.png','concept-aliases.png','concept-aliases-mobile.png','concept-claims.png','concept-claims-mobile.png','personal-sources.png','personal-sources-mobile.png','claim-citations.png','claim-citations-mobile.png','source-citations.png','source-citations-mobile.png','claim-usages.png','claim-usages-mobile.png','claim-evidence.png','claim-evidence-mobile.png','evidence-comparison.png','evidence-comparison-mobile.png','brain-network.png','brain-network-mobile.png','roadmaps.png','roadmaps-mobile.png','learning-study.png','learning-study-mobile.png','learning-evidence.png','learning-evidence-mobile.png','concept-learning-evidence.png','concept-learning-evidence-mobile.png','media-learning-evidence.png','media-learning-evidence-mobile.png','evidence-sessions.png','evidence-sessions-mobile.png','activity-timeline.png','activity-timeline-mobile.png','goals.png','goals-mobile.png','tasks.png','tasks-mobile.png','schedules.png','schedules-mobile.png','planning-activity.png','planning-activity-mobile.png','recurrences.png','recurrences-mobile.png','reminders.png','reminders-mobile.png','notifications.png','notifications-mobile.png','notification-preferences.png','notification-preferences-mobile.png','bookmarks.png','bookmarks-mobile.png','questions.png','questions-mobile.png','decisions.png','decisions-mobile.png','record-concepts.png','record-concepts-mobile.png','record-network.png','record-network-mobile.png','record-activity.png','record-activity-mobile.png','note-activity.png','note-activity-mobile.png','note-concept-activity.png','note-concept-activity-mobile.png','metadata-activity.png','metadata-activity-mobile.png','link-activity.png','link-activity-mobile.png','attachment-activity.png','attachment-activity-mobile.png']:(output/filename).write_bytes(base64.b64decode(value))
 else:clean.append(re.sub(r"(__Host-brain_guest=)[A-Za-z0-9_-]+",r"\1[REDACTED]",line))
(output/'validation.txt').write_text('JOB='+name+'\n'+'\n'.join(clean),encoding='utf-8');print('\n'.join(clean))
if not state.get('status',{}).get('succeeded'):raise RuntimeError('Graph browser validation failed')
print('PASS deployed Graph UI browser validation with verified HTTPS')
