"""Canonical graph rebuild; failed destructive rebuild remains paused for safe repair/retry."""
import argparse,json,re,shutil,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if p.returncode:raise RuntimeError('Local graph rebuild command failed')
 return p.stdout
def apply(doc):run(['apply','-f','-'],json.dumps(doc))
def wait(name,ns,seconds=900):
 deadline=time.monotonic()+seconds
 while time.monotonic()<deadline:
  state=json.loads(run(['get','job',name,'-n',ns,'-o','json']))['status']
  if state.get('succeeded',0):return
  if state.get('failed',0):raise RuntimeError('Graph rebuild Job failed: '+name+'; leave consumers paused after reset')
  time.sleep(3)
 raise RuntimeError('Graph rebuild timeout: '+name)
def job(name,ns,image,command,credentials,env=None,source=None,labels=None):
 volumes=[];mounts=[]
 for secret,path in credentials:
  key='v'+str(len(volumes));volumes.append(dict(name=key,secret=dict(secretName=secret,defaultMode=288)));mounts.append(dict(name=key,mountPath=path,readOnly=True))
 volumes.append(dict(name='tmp',emptyDir=dict(sizeLimit='64Mi')));mounts.append(dict(name='tmp',mountPath='/tmp'))
 if source:
  apply(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace=ns),data=source));volumes.append(dict(name='source',configMap=dict(name=name)));mounts.append(dict(name='source',mountPath='/source',readOnly=True))
 doc=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace=ns),spec=dict(backoffLimit=0,activeDeadlineSeconds=1800,ttlSecondsAfterFinished=86400,template=dict(metadata=dict(labels=labels or {}),spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,fsGroup=10001,seccompProfile=dict(type='RuntimeDefault')),containers=[dict(name='rebuild',image=image,command=command,env=env or [],securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='100m',memory='512Mi'),limits=dict(cpu='2',memory='1536Mi')),volumeMounts=mounts)],volumes=volumes))))
 apply(doc)
 if source:
  uid=json.loads(run(['get','job',name,'-n',ns,'-o','json']))['metadata']['uid'];run(['patch','configmap',name,'-n',ns,'--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':uid}]}})])
 return name
p=argparse.ArgumentParser();p.add_argument('--pause',action='store_true');a=p.parse_args()
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
active=json.loads(run(['get','jobs','-n','brain-apps','-l','brain-graph-rebuild=true','-o','json']))['items']
if any(not j.get('status',{}).get('succeeded') and not j.get('status',{}).get('failed') for j in active):raise RuntimeError('Another graph rebuild Job is active')
name='note-graph-rebuild-'+str(int(time.time()))
key=dict(name='SNAPSHOT_KEY',valueFrom=dict(secretKeyRef=dict(name='brain-content-search-snapshot',key='key')))
preflight="""import os,ssl,urllib.request,urllib.error,json,time
ctx=ssl.create_default_context(cafile='/public-trust/ca.crt')
for path in ['note-snapshots','note-link-snapshots']:
 for attempt in range(15):
  req=urllib.request.Request('https://content-service.brain-apps.svc:8080/internal/content/'+path+'?limit=1',headers={'Authorization':'Bearer '+os.environ['SNAPSHOT_KEY']})
  try:
   with urllib.request.urlopen(req,context=ctx,timeout=8) as response:
    assert response.status==200 and 'no-store' in response.headers['Cache-Control'];assert isinstance(json.load(response)['items'],list)
   break
  except urllib.error.HTTPError:raise
  except urllib.error.URLError:
   if attempt==14:raise
   time.sleep(2)
 for suffix,headers,status in [('?limit=6',{},400),('',{'Cookie':'guest=anything'},403)]:
  req=urllib.request.Request('https://content-service.brain-apps.svc:8080/internal/content/'+path+suffix,headers={'Authorization':'Bearer '+os.environ['SNAPSHOT_KEY'],**headers})
  try:urllib.request.urlopen(req,context=ctx,timeout=8);raise AssertionError('Invalid snapshot boundary accepted')
  except urllib.error.HTTPError as error:assert error.code==status
print('PASS both canonical HTTPS snapshot APIs; browser credentials and page overflow rejected')
"""
label={'brain-graph-rebuild':'true','brain-note-rebuild':'true'}
pre=job(name+'-preflight','brain-apps','python:3.13.7-alpine',['python','-c',preflight],[('brain-guest-api-trust','/public-trust')],[key],labels=label);wait(pre,'brain-apps',120)
deployment=json.loads(run(['get','deployment','brain-query-service','-n','brain-apps','-o','json']));replicas=deployment['spec']['replicas'];image=deployment['spec']['template']['spec']['containers'][0]['image']
if not re.fullmatch(r'knowledge-brain/brain-query-service:local-[0-9a-f]{16}',image):raise RuntimeError('Expected verified local Query image')
proof=root/'.local/note-graph-rebuild-state.json'
if replicas==0:replicas=json.loads(proof.read_text()).get('replicas',1) if proof.exists() else 1
proof.write_text(json.dumps(dict(replicas=replicas,job=name,state='paused'),indent=2),encoding='utf-8')
run(['scale','deployment/brain-query-service','-n','brain-apps','--replicas=0'])
deadline=time.monotonic()+90
while any(any(o.get('kind')=='ReplicaSet' for o in pod['metadata'].get('ownerReferences',[])) for pod in json.loads(run(['get','pods','-n','brain-apps','-l','app.kubernetes.io/name=brain-query-service','-o','json']))['items']):
 if time.monotonic()>deadline:raise RuntimeError('Live Query Pods did not stop; graph preserved')
 time.sleep(1)
# Reset only the fixed derived keyspace. Canonical Content, Cassandra PVC and search state stay intact.
reset=job(name+'-reset','brain-data',json.loads((root/'infra/dependencies/cassandra.json').read_text())['image'],['python3','/source/verify.py'],[('brain-graph-admin','/admin')],[dict(name='MODE',value='graph-reset')],{'verify.py':(root/'scripts/Verify-Cassandra.py').read_text(encoding='utf-8')},{'brain-graph-client':'true'});wait(reset,'brain-data',90)
logging={'logback.xml':'<configuration><appender name="console" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger{36} - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="console"/></root></configuration>'}
command=['java','-Dlogback.configurationFile=/source/logback.xml','-cp','/app/resources:/app/classes:/app/libs/*','io.knowledgebrain.brainquery.GraphRebuildRunner']
host=dict(name='BRAIN_QUERY_GRAPH_HOST',value='brain-cassandra.brain-data.svc')
initialize=job(name+'-initialize','brain-data',image,command+['--initialize'],[('brain-graph-admin','/graph-admin')],[host],logging,{'brain-graph-client':'true'});wait(initialize,'brain-data',120)
env=[host,dict(name='BRAIN_QUERY_CONTENT_ORIGIN',value='https://content-service.brain-apps.svc:8080'),dict(name='BRAIN_QUERY_CONTENT_TRUST_FILE',value='/public-trust/ca.crt'),dict(name='BRAIN_QUERY_CONTENT_SNAPSHOT_KEY',valueFrom=dict(secretKeyRef=dict(name='brain-content-search-snapshot',key='key'))),dict(name='BRAIN_QUERY_DATABASE_URL',value='jdbc:postgresql://brain-postgres.brain-data.svc:5432/brain_brainquery'),dict(name='BRAIN_QUERY_DATABASE_PASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-brainquery-db',key='password'))),dict(name='BRAIN_QUERY_KAFKA_BOOTSTRAP_SERVERS',value='brain-kafka-kafka-bootstrap.brain-events.svc:9093'),dict(name='BRAIN_REBUILD_PAUSE',value=str(a.pause).lower())]
job(name,'brain-apps',image,command,[('brain-graph-projector','/graph-projector'),('brain-graph-kafka','/graph-kafka'),('brain-guest-api-trust','/public-trust')],env,logging,label)
print('REBUILD_JOB='+name,flush=True);wait(name,'brain-apps',1650)
run(['scale','deployment/brain-query-service','-n','brain-apps','--replicas='+str(replicas)])
# Bounded polling keeps the coordinator responsive during graph/Kafka rejoin.
for attempt in range(60):
 state=json.loads(run(['get','deployment','brain-query-service','-n','brain-apps','-o','json']))
 if state.get('status',{}).get('availableReplicas',0)==replicas:break
 time.sleep(3)
else:raise RuntimeError('Restored Query Pods did not become Ready')
proof.write_text(json.dumps(dict(replicas=replicas,job=name,state='complete'),indent=2),encoding='utf-8')
print('PASS canonical graph reset/rebuild, dual-topic replay/checkpoint and live Query readiness',flush=True)
