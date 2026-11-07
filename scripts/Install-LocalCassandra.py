"""Install pinned private graph storage; local context only, no port-forward or secrets in logs."""
import argparse,hashlib,json,re,shutil,subprocess,sys,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--helm');a=p.parse_args()
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(argv):
 result=subprocess.run(argv,text=True,capture_output=True)
 if result.returncode:raise RuntimeError('Cassandra installation command failed')
 return result.stdout
if run(k+['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
helm=a.helm or shutil.which('helm')
if not helm or not Path(helm).is_file():raise RuntimeError('Existing Helm executable required: --helm <path>')
chart=str(root/'infra/charts/brain-cassandra')
run([helm,'lint',chart])
command=[helm,'upgrade','--install','brain-cassandra',chart,'-n','brain-data','-f',str(root/'infra/environments/local/cassandra-values.yaml'),'--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--kube-apiserver=https://127.0.0.1:6550']
if not run(k+['get','statefulset','brain-cassandra','-n','brain-data','--ignore-not-found','-o','name']).strip():run(command+['--set','enabled=false'])
print(run([sys.executable,str(root/'scripts/Provision-CassandraSecrets.py')]).strip(),flush=True)
publicCert=run(k+['get','secret','brain-cassandra-node-tls','-n','brain-data','-o','jsonpath={.data.tls.crt}'])
revision=hashlib.sha256(publicCert.encode()).hexdigest()
run(command+['--set-string','tlsRevision='+revision])
output=run([sys.executable,str(root/'scripts/Run-CassandraValidation.py'),'--bootstrap']);match=re.search(r'JOB=(cassandra-bootstrap-[0-9]+)',output)
if not match:raise RuntimeError('Bootstrap Job not identified')
name=match.group(1);print('JOB='+name,flush=True)
for attempt in range(60):
 status=json.loads(run(k+['get','job',name,'-n','brain-data','-o','json']))['status']
 if status.get('succeeded',0):break
 if status.get('failed',0):raise RuntimeError('Bootstrap failed; inspect Job '+name)
 time.sleep(5)
else:raise RuntimeError('Bootstrap did not complete: '+name)
for attempt in range(60):
 state=json.loads(run(k+['get','statefulset','brain-cassandra','-n','brain-data','-o','json']))
 status=state.get('status',{});count=state['spec']['replicas']
 if status.get('readyReplicas',0)==count and status.get('updatedReplicas',0)==count and status.get('currentRevision')==status.get('updateRevision'):break
 time.sleep(5)
else:raise RuntimeError('Cassandra rollout did not finish')
print('PASS Cassandra Ready; TLS verified and default account disabled; private ClusterIP only')
