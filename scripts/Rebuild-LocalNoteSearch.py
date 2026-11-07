"""Local canonical rebuild. Failure after reset leaves consumers paused for a safe retry."""
import argparse,json,shutil,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if p.returncode:raise RuntimeError(p.stderr or 'Local rebuild command failed')
 return p.stdout
def apply(doc):run(['apply','-f','-'],json.dumps(doc))
def wait(name,ns,seconds=300):
 until=time.monotonic()+seconds
 while time.monotonic()<until:
  job=json.loads(run(['get','job',name,'-n',ns,'-o','json']))
  if job.get('status',{}).get('succeeded'):return
  if job.get('status',{}).get('failed'):raise RuntimeError('Rebuild Job failed: '+name+'; consumers remain paused if reset started')
  time.sleep(2)
 raise RuntimeError('Rebuild Job timed out: '+name)
def job(name,ns,image,command,volumes,mounts,env=None,labels=None):
 return dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace=ns),spec=dict(backoffLimit=0,activeDeadlineSeconds=900,ttlSecondsAfterFinished=86400,template=dict(metadata=dict(labels=labels or {}),spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,fsGroup=10001,seccompProfile=dict(type='RuntimeDefault')),containers=[dict(name='rebuild',image=image,command=command,env=env or [],securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='100m',memory='256Mi'),limits=dict(cpu='1',memory='768Mi')),volumeMounts=mounts)],volumes=volumes))))
def secret(name,mount):return dict(name=mount,secret=dict(secretName=name,defaultMode=288)),dict(name=mount,mountPath='/'+mount,readOnly=True)
p=argparse.ArgumentParser();p.add_argument('--pause',action='store_true',help='Validation handshake: touch /tmp/rebuild.resume in the rebuild Pod');a=p.parse_args()
assert run(['config','current-context']).strip()=='k3d-knowledge-brain','Local context required'
existing=json.loads(run(['get','jobs','-n','brain-apps','-l','brain-note-rebuild=true','-o','json']))['items']
if any(not j.get('status',{}).get('succeeded') and not j.get('status',{}).get('failed') for j in existing):raise RuntimeError('Another rebuild Job is still active; wait or inspect it before retrying')
stamp=str(int(time.time()));name='note-search-rebuild-'+stamp
# Credential and HTTPS preflight completes before any derived data is removed.
preflight="""import os,ssl,urllib.request,urllib.error,json,time
ctx=ssl.create_default_context(cafile='/content-trust/ca.crt')
req=urllib.request.Request('https://content-service.brain-apps.svc:8080/internal/content/note-snapshots?limit=1',headers={'Authorization':'Bearer '+os.environ['SNAPSHOT_KEY']})
for attempt in range(10):
 try:
  with urllib.request.urlopen(req,context=ctx,timeout=8) as r:
   assert r.status==200 and 'no-store' in r.headers['Cache-Control'];assert isinstance(json.load(r)['items'],list)
  break
 except urllib.error.HTTPError:raise
 except urllib.error.URLError:
  if attempt==9:raise
  time.sleep(2)
for suffix,headers,expected in [('?limit=6',{},400),('',{'Cookie':'guest=anything'},403)]:
 req=urllib.request.Request('https://content-service.brain-apps.svc:8080/internal/content/note-snapshots'+suffix,headers={'Authorization':'Bearer '+os.environ['SNAPSHOT_KEY'],**headers})
 try:
  urllib.request.urlopen(req,context=ctx,timeout=8);raise AssertionError('Invalid snapshot boundary accepted')
 except urllib.error.HTTPError as error:assert error.code==expected
print('PASS canonical snapshot HTTPS preflight, cookie rejection and page bounds')
"""
v,m=secret('brain-guest-api-trust','content-trust');keyenv=dict(name='SNAPSHOT_KEY',valueFrom=dict(secretKeyRef=dict(name='brain-content-search-snapshot',key='key')))
apply(job(name+'-preflight','brain-apps','python:3.13.7-alpine',['python','-c',preflight],[v],[m],[keyenv],{'brain-note-rebuild':'true'}));wait(name+'-preflight','brain-apps',90)
deploy=json.loads(run(['get','deployment','brain-query-service','-n','brain-apps','-o','json']));image=deploy['spec']['template']['spec']['containers'][0]['image'];replicas=deploy['spec']['replicas']
proof=root/'.local/note-search-rebuild-state.json'
if replicas==0:
 previous=json.loads(proof.read_text(encoding='utf-8')) if proof.exists() else {}
 replicas=previous.get('replicas',1)
proof.write_text(json.dumps(dict(replicas=replicas,job=name,state='paused'),indent=2),encoding='utf-8')
run(['scale','deployment/brain-query-service','-n','brain-apps','--replicas=0'])
until=time.monotonic()+90
while any(any(o['kind']=='ReplicaSet' for o in pod['metadata'].get('ownerReferences',[])) for pod in json.loads(run(['get','pods','-n','brain-apps','-l','app.kubernetes.io/name=brain-query-service','-o','json']))['items']):
 if time.monotonic()>until:raise RuntimeError('Live consumer Pods did not stop; index was preserved')
 time.sleep(1)
# Separate admin identity resets ONLY the fixed derived index, never canonical tables/PVCs.
meta=json.loads((root/'infra/dependencies/opensearch.json').read_text(encoding='utf-8'))
mapping=json.loads((root/'services/brain-query-service/src/main/resources/search/notes-v1.json').read_text(encoding='utf-8'));mapping['settings']={'number_of_shards':1,'number_of_replicas':0}
apply(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-data'),data={'mapping.json':json.dumps(mapping)}))
reset="""status=$(curl --silent --show-error --retry 5 --retry-connrefused --retry-delay 2 --connect-timeout 3 --max-time 10 --output /tmp/reset --write-out '%{http_code}' --cacert /auth/ca.crt --cert /auth/tls.crt --key /auth/tls.key -X DELETE https://brain-opensearch.brain-data.svc:9200/brain-notes-v1)
[ "$status" = 200 ] || [ "$status" = 404 ] || exit 1
curl --silent --show-error --retry 5 --retry-connrefused --retry-delay 2 --connect-timeout 3 --max-time 10 --fail --cacert /auth/ca.crt --cert /auth/tls.crt --key /auth/tls.key -H 'Content-Type: application/json' -X PUT --data-binary @/source/mapping.json https://brain-opensearch.brain-data.svc:9200/brain-notes-v1 >/dev/null
echo 'PASS reset fixed derived note index'
"""
v,m=secret('brain-search-admin-tls','auth');vol=[v,dict(name='source',configMap=dict(name=name)),dict(name='tmp',emptyDir=dict(sizeLimit='16Mi'))];mnt=[m,dict(name='source',mountPath='/source',readOnly=True),dict(name='tmp',mountPath='/tmp')]
apply(job(name+'-reset','brain-data',meta['image'],['bash','-ec',reset],vol,mnt,labels={'brain-search-operator':'true'}))
uid=json.loads(run(['get','job',name+'-reset','-n','brain-data','-o','json']))['metadata']['uid']
run(['patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name+'-reset','uid':uid}]}})])
wait(name+'-reset','brain-data',90)
vol=[];mnt=[]
for credential,mount in [('brain-guest-api-trust','content-trust'),('brain-query-kafka','kafka'),('brain-search-projector-tls','search-projector')]:
 v,m=secret(credential,mount);vol.append(v);mnt.append(m)
vol.append(dict(name='tmp',emptyDir=dict(sizeLimit='32Mi')));mnt.append(dict(name='tmp',mountPath='/tmp'))
env=[dict(name='BRAIN_QUERY_CONTENT_ORIGIN',value='https://content-service.brain-apps.svc:8080'),dict(name='BRAIN_QUERY_CONTENT_TRUST_FILE',value='/content-trust/ca.crt'),dict(name='BRAIN_QUERY_CONTENT_SNAPSHOT_KEY',valueFrom=dict(secretKeyRef=dict(name='brain-content-search-snapshot',key='key'))),dict(name='BRAIN_QUERY_SEARCH_ORIGIN',value='https://brain-opensearch.brain-data.svc:9200'),dict(name='BRAIN_QUERY_KAFKA_BOOTSTRAP_SERVERS',value='brain-kafka-kafka-bootstrap.brain-events.svc:9093'),dict(name='BRAIN_REBUILD_PAUSE',value='true' if a.pause else 'false')]
apply(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-apps'),data={'logback.xml':'<configuration><appender name="console" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger{36} - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="console"/></root></configuration>'}))
vol.append(dict(name='logging',configMap=dict(name=name)));mnt.append(dict(name='logging',mountPath='/rebuild-config',readOnly=True))
apply(job(name,'brain-apps',image,['java','-Dlogback.configurationFile=/rebuild-config/logback.xml','-cp','/app/resources:/app/classes:/app/libs/*','io.knowledgebrain.brainquery.NoteRebuildRunner'],vol,mnt,env,{'brain-note-rebuild':'true'}))
uid=json.loads(run(['get','job',name,'-n','brain-apps','-o','json']))['metadata']['uid']
run(['patch','configmap',name,'-n','brain-apps','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':uid}]}})])
print('REBUILD_JOB='+name,flush=True);wait(name,'brain-apps',840)
run(['scale','deployment/brain-query-service','-n','brain-apps','--replicas='+str(replicas)])
run(['rollout','status','deployment/brain-query-service','-n','brain-apps','--timeout=60s'])
proof.write_text(json.dumps(dict(replicas=replicas,job=name,state='complete'),indent=2),encoding='utf-8')
print('PASS canonical rebuild, Kafka checkpoint and live consumer readiness',flush=True)
