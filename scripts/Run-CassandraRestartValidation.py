"""Replace only the local Cassandra Pod, retaining PVC, then verify CQL and JanusGraph data."""
import json,re,shutil,subprocess,sys,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(argv):
 p=subprocess.run(argv,text=True,capture_output=True)
 if p.returncode:raise RuntimeError('Local graph restart validation command failed')
 return p.stdout
def job(script,*args):
 result=run([sys.executable,str(root/'scripts'/script),*args]);match=re.search(r'JOB=([a-z0-9-]+)',result)
 if not match:raise RuntimeError('Validation Job not identified')
 name=match.group(1);print('JOB='+name,flush=True)
 for attempt in range(100):
  state=json.loads(run(k+['get','job',name,'-n','brain-data','-o','json']))['status']
  if state.get('succeeded',0):break
  if state.get('failed',0):raise RuntimeError('Validation failed: '+name)
  time.sleep(5)
 else:raise RuntimeError('Validation deadline exceeded: '+name)
 return name
if run(k+['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
# Establish both physical storage and graph-level proof before touching the Pod.
beforeCql=job('Run-CassandraValidation.py')
beforeGraph=job('Run-BackendValidation.py','--brain-query','--graph-storage','--graph-only')
old=json.loads(run(k+['get','pod','brain-cassandra-0','-n','brain-data','-o','json']))
pvc=json.loads(run(k+['get','pvc','data-brain-cassandra-0','-n','brain-data','-o','json']))
if old['metadata']['labels'].get('app')!='brain-cassandra' or not any(c.get('type')=='Ready' and c.get('status')=='True' for c in old['status'].get('conditions',[])):raise RuntimeError('Expected Ready local Cassandra Pod')
run(k+['delete','pod','brain-cassandra-0','-n','brain-data','--wait=true','--timeout=60s'])
print('Replaced local Cassandra Pod; PVC retained',flush=True)
for attempt in range(60):
 raw=run(k+['get','pod','brain-cassandra-0','-n','brain-data','--ignore-not-found','-o','json'])
 if not raw.strip():time.sleep(5);continue
 current=json.loads(raw)
 if any(c.get('type')=='Ready' and c.get('status')=='True' for c in current['status'].get('conditions',[])):break
 time.sleep(5)
else:raise RuntimeError('Replacement Cassandra Pod did not become Ready')
new=json.loads(run(k+['get','pod','brain-cassandra-0','-n','brain-data','-o','json']))
newPvc=json.loads(run(k+['get','pvc','data-brain-cassandra-0','-n','brain-data','-o','json']))
if old['metadata']['uid']==new['metadata']['uid'] or pvc['metadata']['uid']!=newPvc['metadata']['uid']:raise RuntimeError('Pod/PVC replacement evidence mismatch')
afterCql=job('Run-CassandraValidation.py','--restart')
afterGraph=job('Run-BackendValidation.py','--brain-query','--graph-storage','--graph-restart')
proof=dict(oldPodUid=old['metadata']['uid'],newPodUid=new['metadata']['uid'],pvcUid=pvc['metadata']['uid'],beforeCqlJob=beforeCql,beforeGraphJob=beforeGraph,afterCqlJob=afterCql,afterGraphJob=afterGraph)
(root/'.local/cassandra-restart-proof.json').write_text(json.dumps(proof,indent=2)+'\n',encoding='utf-8')
print('PASS actual Pod UID changed, PVC UID retained, CQL and JanusGraph data survived',flush=True)
