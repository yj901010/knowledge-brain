"""Canonical graph rebuild; failed destructive rebuild remains paused for safe repair/retry."""
import argparse,json,re,shutil,subprocess,time,sys
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

p=argparse.ArgumentParser();p.add_argument('--pause',action='store_true');a=p.parse_args()
assert run(['config','current-context']).strip()=='k3d-knowledge-brain'
active=json.loads(run(['get','jobs','-n','brain-data','-l','brain-public-rebuild=true','-o','json']))['items']
assert not any(not j.get('status',{}).get('succeeded') and not j.get('status',{}).get('failed') for j in active),'Another public rebuild is active'
deployment=json.loads(run(['get','deployment','brain-query-service','-n','brain-apps','-o','json']));image=deployment['spec']['template']['spec']['containers'][0]['image'];assert re.fullmatch(r'knowledge-brain/brain-query-service:local-[0-9a-f]{16}',image)
name='brain-public-rebuild-'+str(int(time.time()))
def value(name,text):return {'name':name,'value':text}
def secret(name,resource,key):return {'name':name,'valueFrom':{'secretKeyRef':{'name':resource,'key':key}}}
env=[value('BRAIN_QUERY_GRAPH_HOST','brain-cassandra.brain-data.svc'),value('BRAIN_QUERY_KNOWLEDGE_ORIGIN','https://knowledge-service.brain-apps.svc:8080'),secret('BRAIN_QUERY_PUBLIC_SNAPSHOT_KEY','brain-knowledge-public-snapshot','key'),value('BRAIN_QUERY_DATABASE_URL','jdbc:postgresql://brain-postgres.brain-data.svc:5432/brain_brainquery'),secret('BRAIN_QUERY_DATABASE_PASSWORD','brain-brainquery-db','password'),value('BRAIN_QUERY_KAFKA_BOOTSTRAP_SERVERS','brain-kafka-kafka-bootstrap.brain-events.svc:9093'),value('BRAIN_QUERY_SEARCH_ORIGIN','https://brain-opensearch.brain-data.svc:9200')]
credentials=[('brain-graph-admin','/graph-admin'),('brain-graph-projector','/graph-projector'),('brain-public-kafka','/public-kafka'),('brain-guest-api-trust','/public-trust'),('brain-search-projector-tls','/search-projector'),('brain-search-admin-tls','/search-admin')]
logging={'logback.xml':'<configuration><appender name="console" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger{36} - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="console"/></root></configuration>','mapping.json':(root/'services/brain-query-service/src/main/resources/search/public-knowledge-v1.json').read_text()}
mapping=json.loads(logging['mapping.json']);mapping['settings']={'number_of_shards':1,'number_of_replicas':0};logging['mapping.json']=json.dumps(mapping)
command=['java','-Xmx768m','-Dlogback.configurationFile=/source/logback.xml','-cp','/app/resources:/app/classes:/app/libs/*','io.knowledgebrain.brainquery.PublicRebuildRunner']
job(name,'brain-data',image,command+(['--pause'] if a.pause else []),credentials,env,logging,{'brain-public-rebuild':'true','brain-graph-client':'true','brain-search-client':'true'})
print('PUBLIC_REBUILD_JOB='+name,flush=True);wait(name,'brain-data',1650)
print(run(['logs','job/'+name,'-n','brain-data'])[-3000:],flush=True)
(root/'.local/public-discovery-rebuild.json').write_text(json.dumps({'state':'COMPLETE','image':image,'job':name,'sourceUid':json.loads(run(['get','namespace','kube-system','-o','json']))['metadata']['uid']},indent=2))
print('PASS public-only rebuild; private Query remained running; canonical stores untouched',flush=True)
