"""Canonical graph rebuild; failed destructive rebuild remains paused for safe repair/retry."""
import argparse,json,re,shutil,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,encoding="utf-8",capture_output=True)
 if p.returncode:raise RuntimeError('Local network rebuild command failed')
 return p.stdout
def apply(doc):run(['apply','-f','-'],json.dumps(doc))
def wait(name,ns,seconds=900):
 deadline=time.monotonic()+seconds
 while time.monotonic()<deadline:
  state=json.loads(run(['get','job',name,'-n',ns,'-o','json']))['status']
  if state.get('succeeded',0):return
  if state.get('failed',0):raise RuntimeError('Network operator Job failed: '+name+'; inspect its logs before retrying')
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

p=argparse.ArgumentParser();p.add_argument('--pause',action='store_true');p.add_argument('--initialize',action='store_true');p.add_argument('--refresh-records',action='store_true');a=p.parse_args()
assert run(['config','current-context']).strip()=='k3d-knowledge-brain'
active=json.loads(run(['get','jobs','-n','brain-apps','-l','brain-network-rebuild=true','-o','json']))['items']
assert not any(not j.get('status',{}).get('succeeded') and not j.get('status',{}).get('failed') for j in active),'Another network rebuild is active'
deployment=json.loads(run(['get','deployment','brain-query-service','-n','brain-apps','-o','json']));image=deployment['spec']['template']['spec']['containers'][0]['image']
assert re.fullmatch(r'knowledge-brain/brain-query-service:local-[0-9a-f]{16}',image)
name='brain-network-rebuild-'+str(int(time.time()))
logging={'logback.xml':'<configuration><appender name="console" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger{36} - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="console"/></root></configuration>'}
command=['java','-Dlogback.configurationFile=/source/logback.xml','-cp','/app/resources:/app/classes:/app/libs/*','io.knowledgebrain.brainquery.BrainRebuildRunner']
host={'name':'BRAIN_QUERY_GRAPH_HOST','value':'brain-cassandra.brain-data.svc'}
if a.initialize:
 initialize=job(name+'-initialize','brain-data',image,command+['--initialize'],[('brain-graph-admin','/graph-admin')],[host],logging,{'brain-graph-client':'true'});wait(initialize,'brain-data',120);print('PASS network schema initialized');raise SystemExit(0)
def value(name,text):return {'name':name,'value':text}
def secret(name,resource,key):return {'name':name,'valueFrom':{'secretKeyRef':{'name':resource,'key':key}}}
env=[host,value('BRAIN_QUERY_CONTENT_ORIGIN','https://content-service.brain-apps.svc:8080'),value('BRAIN_QUERY_KNOWLEDGE_ORIGIN','https://knowledge-service.brain-apps.svc:8080'),value('BRAIN_QUERY_CONTENT_TRUST_FILE','/public-trust/ca.crt'),secret('BRAIN_QUERY_CONTENT_SNAPSHOT_KEY','brain-content-search-snapshot','key'),secret('BRAIN_QUERY_KNOWLEDGE_SNAPSHOT_KEY','brain-knowledge-network-snapshot','key'),value('BRAIN_QUERY_DATABASE_URL','jdbc:postgresql://brain-postgres.brain-data.svc:5432/brain_brainquery'),secret('BRAIN_QUERY_DATABASE_PASSWORD','brain-brainquery-db','password'),value('BRAIN_QUERY_KAFKA_BOOTSTRAP_SERVERS','brain-kafka-kafka-bootstrap.brain-events.svc:9093'),value('BRAIN_QUERY_SEARCH_ORIGIN','https://brain-opensearch.brain-data.svc:9200'),value('BRAIN_REBUILD_PAUSE',str(a.pause).lower())]
credentials=[('brain-graph-projector','/graph-projector'),('brain-network-kafka','/network-kafka'),('brain-guest-api-trust','/public-trust'),('brain-search-projector-tls','/search-projector')]
labels={'brain-network-rebuild':'true','brain-graph-rebuild':'true','brain-note-rebuild':'true'}
if a.refresh_records:
 if a.pause or a.initialize:raise ValueError('Record refresh cannot reset/pause projections')
 env=[item for item in env if item['name'] not in ('BRAIN_QUERY_KNOWLEDGE_ORIGIN','BRAIN_QUERY_KNOWLEDGE_SNAPSHOT_KEY')]
 job(name,'brain-apps',image,command+['--refresh-records'],credentials,env,logging,labels);print('RECORD_REFRESH_JOB='+name,flush=True);wait(name,'brain-apps',1650)
 print('PASS non-destructive six-feed record refresh; no consumer pause, store reset or Kafka checkpoint mutation',flush=True);raise SystemExit(0)
pre=job(name+'-preflight','brain-apps',image,command+['--preflight'],credentials,env,logging,labels);wait(pre,'brain-apps',120)
proof=root/'.local/brain-network-rebuild-state.json';replicas=deployment['spec']['replicas']
if replicas==0:
 assert proof.exists(),'Missing original replicas for paused repair'
 replicas=json.loads(proof.read_text())['replicas']
proof.write_text(json.dumps({'replicas':replicas,'image':image,'job':name,'state':'paused'},indent=2),encoding='utf-8')
run(['scale','deployment/brain-query-service','-n','brain-apps','--replicas=0'])
deadline=time.monotonic()+90
while json.loads(run(['get','pods','-n','brain-apps','-l','app.kubernetes.io/name=brain-query-service','-o','json']))['items']:
 if time.monotonic()>deadline:raise RuntimeError('Query Pods did not stop; projections preserved')
 time.sleep(1)
# Only the disposable typed vertices and fixed network index are reset. Old Note projections remain.
result=subprocess.run([__import__('sys').executable,str(root/'scripts/Initialize-NetworkSearch.py'),'--reset'],text=True,encoding='utf-8',capture_output=True)
if result.returncode:raise RuntimeError('Network index reset failed; Query remains paused')
print(result.stdout.strip(),flush=True)
reset=job(name+'-reset','brain-data',image,command+['--reset-graph'],[('brain-graph-admin','/graph-admin')],[host],logging,{'brain-graph-client':'true'});wait(reset,'brain-data',180)
job(name,'brain-apps',image,command,credentials,env,logging,labels);print('REBUILD_JOB='+name,flush=True);wait(name,'brain-apps',1650)
run(['scale','deployment/brain-query-service','-n','brain-apps','--replicas='+str(replicas)])
for attempt in range(60):
 state=json.loads(run(['get','deployment','brain-query-service','-n','brain-apps','-o','json']))
 if state.get('status',{}).get('availableReplicas',0)==replicas:break
 time.sleep(3)
else:raise RuntimeError('Restored Query Pods did not become Ready')
proof.write_text(json.dumps({'replicas':replicas,'image':image,'job':name,'state':'complete'},indent=2),encoding='utf-8')
print('PASS isolated network reset, canonical eleven-feed rebuild, Kafka tails and restored Query readiness',flush=True)
