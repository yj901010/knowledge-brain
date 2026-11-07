"""Install pinned private OpenSearch using the existing operator Helm executable."""
import argparse,json,re,shutil,subprocess,sys,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--helm');args=p.parse_args()
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def command(argv):
 result=subprocess.run(argv,text=True,capture_output=True)
 if result.returncode:raise RuntimeError(result.stderr or 'Command failed')
 return result.stdout
assert command(k+['config','current-context']).strip()=='k3d-knowledge-brain','Local context required'
helm=args.helm or shutil.which('helm')
if not helm or not Path(helm).is_file():raise RuntimeError('Existing Helm executable required: --helm <path>')
print(command([sys.executable,str(root/'scripts/Provision-OpenSearchTls.py')]).strip(),flush=True)
command([helm,'lint',str(root/'infra/charts/brain-opensearch')])
command([helm,'upgrade','--install','brain-opensearch',str(root/'infra/charts/brain-opensearch'),'-n','brain-data','-f',str(root/'infra/environments/local/opensearch-values.yaml'),'--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--kube-apiserver=https://127.0.0.1:6550'])
print('Pinned OpenSearch release applied; initializing fixed security roles/index',flush=True)
output=command([sys.executable,str(root/'scripts/Initialize-OpenSearch.py')]);match=re.search(r'JOB=(opensearch-initialize-[0-9]+)',output)
if not match:raise RuntimeError('Initialization Job was not identified')
name=match.group(1)
for attempt in range(35):
 status=json.loads(command(k+['get','job',name,'-n','brain-data','-o','json']))['status']
 conditions=status.get('conditions',[])
 if any(c['type']=='Complete' and c['status']=='True' for c in conditions):break
 if any(c['type']=='Failed' and c['status']=='True' for c in conditions):raise RuntimeError('OpenSearch initialization failed; inspect Job '+name)
 time.sleep(10)
else:raise RuntimeError('Initialization did not finish: '+name)
command(k+['rollout','status','statefulset/brain-opensearch','-n','brain-data','--timeout=60s'])
print('PASS OpenSearch Ready with mTLS and separate reader/projector roles; no external port opened')
