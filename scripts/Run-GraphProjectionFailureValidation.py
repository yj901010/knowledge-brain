"""Local-only reversible consumer/storage outage and concurrent canonical rebuild proof."""
import json,re,shutil,subprocess,sys,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args):
 p=subprocess.run(k+args,text=True,capture_output=True)
 if p.returncode:raise RuntimeError('Confined graph validation operation failed')
 return p.stdout
def obj(args):return json.loads(run(args+['-o','json']))
def scale(kind,name,ns,n):run(['scale',kind+'/'+name,'-n',ns,'--replicas='+str(n)])
def wait_marker(name,ns,marker,seconds=240):
 deadline=time.monotonic()+seconds
 while time.monotonic()<deadline:
  state=obj(['get','job',name,'-n',ns])['status']
  if state.get('failed'):raise RuntimeError('Graph verification Job failed: '+name)
  result=subprocess.run(k+['logs','job/'+name,'-n',ns],text=True,capture_output=True)
  log=result.stdout if result.returncode==0 else ''
  if marker in log:return
  time.sleep(2)
 raise RuntimeError('Graph validation handshake timeout: '+marker)
def pod(name,ns):
 pods=obj(['get','pods','-n',ns,'-l','job-name='+name])['items']
 if len(pods)!=1 or not any(o.get('kind')=='Job' and o.get('name')==name for o in pods[0]['metadata'].get('ownerReferences',[])):raise RuntimeError('Expected one owned verification Pod')
 return pods[0]['metadata']['name']
def touch(name,ns,file):run(['exec','-n',ns,pod(name,ns),'--','touch','/tmp/'+file])
def wait_workload(kind,name,ns,replicas,selector,seconds=240):
 deadline=time.monotonic()+seconds
 while time.monotonic()<deadline:
  pods=obj(['get','pods','-n',ns,'-l',selector])['items']
  if replicas==0 and not pods:return
  if replicas and len(pods)==replicas and all(any(c['type']=='Ready' and c['status']=='True' for c in p.get('status',{}).get('conditions',[])) for p in pods):return
  time.sleep(2)
 raise RuntimeError('Workload restoration timeout: '+name)
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
query=obj(['get','deployment','brain-query-service','-n','brain-apps'])['spec']['replicas'];cass=obj(['get','statefulset','brain-cassandra','-n','brain-data'])['spec']['replicas']
if query<1 or cass!=1:raise RuntimeError('Expected running local single Cassandra and Query')
result=subprocess.run([sys.executable,str(root/'scripts/Run-GraphProjectionValidation.py'),'--failures','--rebuild'],text=True,capture_output=True,check=True)
name=result.stdout.strip().split('JOB=')[-1]
if not re.fullmatch(r'graph-projection-validation-[0-9]+',name):raise RuntimeError('Unexpected validation Job')
print('JOB='+name,flush=True)
qsel='app.kubernetes.io/name=brain-query-service';csel='app=brain-cassandra'
proof={'job':name,'cassandraPvcUid':obj(['get','pvc','data-brain-cassandra-0','-n','brain-data'])['metadata']['uid']}
try:
 wait_marker(name,'brain-data','GRAPH_PROJECTOR_OUTAGE_READY')
 scale('deployment','brain-query-service','brain-apps',0);wait_workload('deployment','brain-query-service','brain-apps',0,qsel);touch(name,'brain-data','projector.down')
 wait_marker(name,'brain-data','GRAPH_PROJECTOR_OFFLINE_WRITE_DONE');scale('deployment','brain-query-service','brain-apps',query);wait_workload('deployment','brain-query-service','brain-apps',query,qsel);touch(name,'brain-data','projector.up')
 wait_marker(name,'brain-data','GRAPH_STORAGE_OUTAGE_READY');proof['oldCassandraPodUid']=obj(['get','pod','brain-cassandra-0','-n','brain-data'])['metadata']['uid']
 scale('statefulset','brain-cassandra','brain-data',0);wait_workload('statefulset','brain-cassandra','brain-data',0,csel);touch(name,'brain-data','cassandra.down')
 wait_marker(name,'brain-data','GRAPH_STORAGE_OFFLINE_WRITE_DONE')
 for attempt in range(45):
  ready=obj(['get','deployment','brain-query-service','-n','brain-apps']).get('status',{}).get('availableReplicas',0)
  if ready==0:break
  time.sleep(2)
 else:raise RuntimeError('Cassandra outage did not fail actual Query readiness')
 scale('statefulset','brain-cassandra','brain-data',cass);wait_workload('statefulset','brain-cassandra','brain-data',cass,csel);wait_workload('deployment','brain-query-service','brain-apps',query,qsel);proof['newCassandraPodUid']=obj(['get','pod','brain-cassandra-0','-n','brain-data'])['metadata']['uid'];touch(name,'brain-data','cassandra.up')
 wait_marker(name,'brain-data','GRAPH_CANONICAL_REBUILD_READY')
 rebuild=subprocess.Popen([sys.executable,str(root/'scripts/Rebuild-LocalNoteGraph.py'),'--pause'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 deadline=time.monotonic()+900;rebuild_name=None
 while time.monotonic()<deadline:
  jobs=obj(['get','jobs','-n','brain-apps','-l','brain-graph-rebuild=true'])['items']
  active=[j for j in jobs if re.fullmatch(r'note-graph-rebuild-[0-9]+',j['metadata']['name']) and not j.get('status',{}).get('succeeded') and not j.get('status',{}).get('failed')]
  if active:rebuild_name=sorted(active,key=lambda j:j['metadata']['creationTimestamp'])[-1]['metadata']['name'];break
  if rebuild.poll() is not None:raise RuntimeError('Concurrent rebuild failed before snapshot')
  time.sleep(3)
 if not rebuild_name:raise RuntimeError('Concurrent rebuild did not start')
 proof['rebuildJob']=rebuild_name
 wait_marker(rebuild_name,'brain-apps','GRAPH_REBUILD_SNAPSHOT_READY',900);touch(name,'brain-data','rebuild.scanned');wait_marker(name,'brain-data','GRAPH_CANONICAL_CONCURRENT_WRITE_DONE');time.sleep(3);touch(rebuild_name,'brain-apps','rebuild.resume')
 if rebuild.wait(timeout=360)!=0:raise RuntimeError('Concurrent canonical rebuild failed')
 touch(name,'brain-data','rebuild.complete');wait_marker(name,'brain-data','PASS actual target/source retirement',240)
 run(['wait','job/'+name,'-n','brain-data','--for=condition=complete','--timeout=60s'])
 assert proof['oldCassandraPodUid']!=proof['newCassandraPodUid'];assert proof['cassandraPvcUid']==obj(['get','pvc','data-brain-cassandra-0','-n','brain-data'])['metadata']['uid']
 proof['state']='complete';(root/'.local/note-graph-failure-proof.json').write_text(json.dumps(proof,indent=2),encoding='utf-8');print('PASS consumer outage, Cassandra Pod replacement, readiness failure/recovery and concurrent dual-topic canonical rebuild',flush=True)
finally:
 scale('statefulset','brain-cassandra','brain-data',cass)
 state=root/'.local/note-graph-rebuild-state.json'
 if not state.exists() or json.loads(state.read_text())['state']=='complete':scale('deployment','brain-query-service','brain-apps',query)
