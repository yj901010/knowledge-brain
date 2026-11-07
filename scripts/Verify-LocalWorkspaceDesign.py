"""Read-only runtime/source comparison after scoped workspace browser checks."""
import argparse,hashlib,importlib.util,json,re,sys,tarfile,time
from pathlib import Path
from local_recovery import Cluster,ROOT,now,write_json

p=argparse.ArgumentParser();p.add_argument('--browser-job',action='append',required=True);a=p.parse_args()
c=Cluster(ROOT/'.local/kubeconfig.yaml','knowledge-brain');baseline=json.loads((ROOT/'.local/workspace-design-baseline.json').read_text());delivery=json.loads((ROOT/'.local/workspace-design-deployment.json').read_text());assert c.uid()==baseline['sourceUid']==delivery['sourceUid']
image=delivery['services']['gateway']['image'];results=[];principals=set();total=0
for name in a.browser_job:
 job=c.get('job',name,'brain-data');assert job['status'].get('succeeded')==1
 annotations=job['metadata']['annotations'];assert annotations['brain.knowledge/gateway-image']==image and annotations['brain.knowledge/kube-system-uid']==c.uid()
 logs=c.run('logs','job/'+name,'-n','brain-data').decode();count=re.search(r'^\s*(\d+) passed\b',logs,re.M);assert count and 'GRAPH_UI_VALIDATION_EXIT_CODE=0' in logs
 passed=int(count.group(1));total+=passed;principals.update(re.findall(r'PUBLIC_REVIEWER_READY=([0-9a-f-]{36})',logs))
 clean='\n'.join(s for s in logs.splitlines() if not s.startswith('GRAPH_UI_IMAGE='));target=ROOT/'.local'/(name+'-workspace.log');target.write_text(clean,encoding='utf-8')
 results.append(dict(job=name,uid=job['metadata']['uid'],passed=passed,log=str(target),logSha256=hashlib.sha256(clean.encode()).hexdigest()))
assert total>=12 and len(principals)==1
assert all(re.fullmatch('[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}',v) for v in principals)
selection=','.join("'"+v+"'" for v in sorted(principals));name='workspace-authority-'+str(int(time.time()))
sql=f"BEGIN READ ONLY; SELECT json_build_object('fixtureRows',(SELECT count(*) FROM space_membership WHERE principal_id IN ({selection}) AND role='REVIEWER'),'activeReviewers',(SELECT count(*) FROM space_membership WHERE principal_id IN ({selection}) AND role='REVIEWER' AND state='ACTIVE'),'activeCapabilities',(SELECT count(*) FROM public_knowledge_capability WHERE principal_id IN ({selection}) AND state='ACTIVE')); ROLLBACK;"
c.apply([dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-data'),data={'verify.sql':sql})])
spec=dict(backoffLimit=0,activeDeadlineSeconds=120,ttlSecondsAfterFinished=86400,template=dict(spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,runAsGroup=10001,seccompProfile=dict(type='RuntimeDefault')),containers=[dict(name='verify',image='postgres:17.11-bookworm',command=['psql','-X','-t','-A','-v','ON_ERROR_STOP=1','-h','brain-postgres.brain-data.svc','-U','brain_space','-d','brain_space','-f','/source/verify.sql'],env=[dict(name='PGPASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-space-db',key='password')))],resources=dict(requests=dict(cpu='50m',memory='32Mi'),limits=dict(cpu='250m',memory='128Mi')),securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),volumeMounts=[dict(name='source',mountPath='/source',readOnly=True)])],volumes=[dict(name='source',configMap=dict(name=name))])))
c.apply([dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=spec)]);uid=c.get('job',name,'brain-data')['metadata']['uid'];c.run('patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps(dict(metadata=dict(ownerReferences=[dict(apiVersion='batch/v1',kind='Job',name=name,uid=uid)]))));c.run('wait','job/'+name,'-n','brain-data','--for=condition=Complete','--timeout=120s',timeout=140)
authority=json.loads(next(s for s in c.run('logs','job/'+name,'-n','brain-data').decode().splitlines() if s.startswith('{')));assert authority['fixtureRows']==1 and authority['activeReviewers']==authority['activeCapabilities']==0
apps=c.get('deployments',namespace='brain-apps')['items'];ops=c.get('deployments',namespace='brain-observability')['items'];assert len(apps)==14 and len(ops)==7 and all(d['status'].get('readyReplicas')==d['spec']['replicas']==1 for d in apps+ops)
for d in apps:
 old=baseline['services'][d['metadata']['name']];assert d['metadata']['uid']==old['uid'];assert d['spec']['template']['spec']['containers'][0]['image']==(image if d['metadata']['name']=='gateway-service' else old['image'])
for d in ops:assert baseline['monitoring'][d['metadata']['name']]==d['metadata']['uid']
volumes=[(p['metadata']['namespace'],p['metadata']['name'],p['metadata']['uid']) for ns in ['brain-data','brain-events','brain-observability'] for p in c.get('pvc',namespace=ns)['items']];assert set(volumes)=={(p['namespace'],p['name'],p['uid']) for p in baseline['volumes']} and len(volumes)==13
nodes=c.get('nodes')['items'];assert len(nodes)==2 and all(any(s['type']=='Ready' and s['status']=='True' for s in n['status']['conditions']) for n in nodes)
e=json.loads(Path(delivery['services']['gateway']['builderEvidence']).read_text());assert e['cleanupCompletedAt'] and len(e['imports'])==2
for field,sha in [('sourceArchive','sourceSha256'),('frontendSourceArchive','frontendSourceSha256')]:assert hashlib.sha256(Path(e[field]).read_bytes()).hexdigest()==e[sha]
assert all(hashlib.sha256((ROOT/path).read_bytes()).hexdigest()==sha for path,sha in e['frontendFiles'].items())
with tarfile.open(e['sourceArchive'],'r:gz') as bundle:
 runtime_files=[ROOT/'pom.xml',ROOT/'services/gateway-service/pom.xml']+[p for directory in [ROOT/'services/gateway-service/src/main',ROOT/'contracts/events/src/main'] for p in directory.rglob('*') if p.is_file()]
 for path in runtime_files:assert bundle.extractfile(path.relative_to(ROOT).as_posix()).read()==path.read_bytes(),str(path)

assert not c.run('get','pod',e['metadata']['pod'],'-n','brain-apps','--ignore-not-found','-o','name').strip()
spec=importlib.util.spec_from_file_location('fault',ROOT/'scripts/Verify-ObservabilityFaults.py');f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
with f.forward('brain-observability','brain-prometheus',19090,9090):
 f.wait(lambda:f.get('http://127.0.0.1:19090/api/v1/status/buildinfo'))
 def query(expr):return f.get('http://127.0.0.1:19090/api/v1/query?'+f.urllib.parse.urlencode(dict(query=expr)))['data']['result']
 f.wait(lambda:len(query('count by (service_name) (jvm_memory_used_bytes)'))==13);assert not query('ALERTS{alertstate="firing"}');assert len(query('brain_cdc_connector_running == 1'))==len(query('brain_cdc_tasks_running == 1'))==10
proof=dict(state='COMPLETE',verifiedAt=now(),sourceUid=c.uid(),image=image,browserPassed=total,browser=results,authority=authority,authorityJob=name,frontendFilesMatch=len(e['frontendFiles']),runtimeFilesMatch=len(runtime_files),nodesReady=2,appsReady=14,monitoringReady=7,jvmServices=13,connectorsRunning=10,pvcUidsUnchanged=True,domainDeploymentUidsUnchanged=True,unchangedOtherAppImages=13,firingAlerts=[],delivery=delivery)
write_json(ROOT/'.local/workspace-design-validation.json',proof);print('PASS workspace design: '+str(total)+' browser scenarios, pinned frontend image/source, retired test authority,14 apps/7 monitoring/13 JVMs/13 PVC identities/10 CDC')
