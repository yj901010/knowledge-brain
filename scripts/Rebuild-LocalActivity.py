"""Replay the derived timeline from retained Learning/Planning/Content/Knowledge events and Activity Inbox. No reset/delete."""
import copy,json,re,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=['C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,encoding='utf-8',capture_output=True)
 if p.returncode:raise RuntimeError('Local Activity replay command failed; inspect the named Job')
 return p.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
# Inspect Pod-template labels as well: earlier replay Jobs had no Job-level label.
jobs=json.loads(run(['get','jobs','-n','brain-apps','-o','json']))['items']
active=[j for j in jobs if j.get('metadata',{}).get('labels',{}).get('brain-activity-rebuild')=='true' or j.get('spec',{}).get('template',{}).get('metadata',{}).get('labels',{}).get('brain-activity-rebuild')=='true']
if any(not any(c.get('type') in ('Complete','Failed') and c.get('status')=='True' for c in j.get('status',{}).get('conditions',[])) for j in active):raise RuntimeError('Another Activity replay is active')
deployment=json.loads(run(['get','deployment','activity-service','-n','brain-apps','-o','json']))
pod=copy.deepcopy(deployment['spec']['template']['spec']);container=pod['containers'][0]
if not re.fullmatch(r'knowledge-brain/activity-service:local-[0-9a-f]{16}',container['image']):raise RuntimeError('Verified local Activity image required')
for key in ['livenessProbe','readinessProbe','startupProbe','ports','args']:container.pop(key,None)
container['name']='replay';container['command']=['java','-XX:MaxRAMPercentage=65.0','-cp','/app/resources:/app/classes:/app/libs/*','io.knowledgebrain.activity.ActivityRebuild'];pod['restartPolicy']='Never';pod['automountServiceAccountToken']=False
# Operator replay has no HTTP listener and must never match the API Service selector.
allowed={'BRAIN_ACTIVITY_DATABASE_URL','BRAIN_ACTIVITY_DATABASE_PASSWORD','BRAIN_ACTIVITY_CLICKHOUSE_ORIGIN','BRAIN_ACTIVITY_TRUST_FILE','BRAIN_ACTIVITY_LEARNING_ORIGIN','BRAIN_ACTIVITY_EXPORT_KEY','BRAIN_ACTIVITY_PLANNING_ORIGIN','BRAIN_ACTIVITY_PLANNING_EXPORT_KEY','BRAIN_ACTIVITY_CONTENT_ORIGIN','BRAIN_ACTIVITY_CONTENT_EXPORT_KEY','BRAIN_ACTIVITY_KNOWLEDGE_ORIGIN','BRAIN_ACTIVITY_KNOWLEDGE_EXPORT_KEY','BRAIN_ACTIVITY_INTEGRATION_ORIGIN','BRAIN_ACTIVITY_INTEGRATION_EXPORT_KEY','JAVA_TOOL_OPTIONS'}
container['env']=[e for e in container.get('env',[]) if e['name'] in allowed]
mounts={'ch-projector','space-trust','temporary'}
container['volumeMounts']=[v for v in container.get('volumeMounts',[]) if v['name'] in mounts]
pod['volumes']=[v for v in pod.get('volumes',[]) if v['name'] in mounts]
pod.pop('initContainers',None)
name='activity-timeline-replay-'+str(int(time.time()))
job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-apps',labels={'brain-activity-rebuild':'true'}),spec=dict(backoffLimit=0,activeDeadlineSeconds=1800,ttlSecondsAfterFinished=86400,template=dict(metadata=dict(labels={'brain-activity-rebuild':'true'}),spec=pod)))
run(['apply','-f','-'],json.dumps(job));print('REPLAY_JOB='+name,flush=True)
print('Readiness stays false if replay fails; repair the dependency and rerun this command. No canonical data, PVC or Kafka offsets are deleted.',flush=True)
