"""Install independently imported Query image, canonical graph rebuild, then enable live graph consumer."""
import argparse,json,re,shutil,subprocess,sys,time
from pathlib import Path
import yaml
root=Path(__file__).resolve().parents[1]
from local_feature_installer_guard import require_tag_baseline
p=argparse.ArgumentParser();p.add_argument('--helm');a=p.parse_args()
require_tag_baseline(__file__, root, vars(a))
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(argv):
 result=subprocess.run(argv,text=True,capture_output=True)
 if result.returncode:raise RuntimeError('Local graph installation command failed')
 return result.stdout
if run(k+['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
helm=a.helm or shutil.which('helm')
if not helm or not Path(helm).is_file():raise RuntimeError('Existing Helm executable required')
meta=json.loads((root/'.local/brain-query-image.json').read_text())
if not re.fullmatch('local-[0-9a-f]{16}',meta['tag']) or meta['image']!='knowledge-brain/brain-query-service:'+meta['tag']:raise RuntimeError('Expected local Query image metadata')
for helper in ['Provision-QueryProjectorSecrets.py','Provision-GraphProjectorSecrets.py']:print(run([sys.executable,str(root/'scripts'/helper)]).strip(),flush=True)
# Staging starts only search on the new artifact; graph stays disabled until canonical rebuild/checkpoint succeeds.
values=yaml.safe_load((root/'infra/environments/local/brain-query-values.yaml').read_text())
for entry in values['env']:
 if entry['name']=='SPRING_PROFILES_ACTIVE':entry['value']='projector'
 if entry['name']=='BRAIN_QUERY_GRAPH_ENABLED':entry['value']='false'
values['image']['tag']=meta['tag'];staging=root/'.local/query-graph-staging.yaml';staging.write_text(yaml.safe_dump(values,sort_keys=False),encoding='utf-8')
command=[helm,'upgrade','--install','brain-query-service',str(root/'infra/charts/brain-service'),'-n','brain-apps','-f',str(root/'infra/services/brain-query-values.yaml'),'-f',str(staging),'--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--kube-apiserver=https://127.0.0.1:6550']
run(command)
run(k+['rollout','status','deployment/brain-query-service','-n','brain-apps','--timeout=60s'])
print('PASS new Query image Ready with graph disabled before canonical bootstrap',flush=True)
process=subprocess.Popen([sys.executable,str(root/'scripts/Rebuild-LocalNoteGraph.py')],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
for line in process.stdout:print(line.strip(),flush=True)
if process.wait():raise RuntimeError('Canonical rebuild failed; inspect graph rebuild state; consumers remain paused')
run([helm,'upgrade','brain-query-service',str(root/'infra/charts/brain-service'),'-n','brain-apps','-f',str(root/'infra/services/brain-query-values.yaml'),'-f',str(root/'infra/environments/local/brain-query-values.yaml'),'--set','image.tag='+meta['tag'],'--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--kube-apiserver=https://127.0.0.1:6550'])
for attempt in range(60):
 state=json.loads(run(k+['get','deployment','brain-query-service','-n','brain-apps','-o','json']))
 if state.get('status',{}).get('observedGeneration',0)>=state['metadata']['generation'] and state.get('status',{}).get('updatedReplicas',0)==state['spec']['replicas'] and state.get('status',{}).get('availableReplicas',0)==state['spec']['replicas']:break
 time.sleep(3)
else:raise RuntimeError('Graph consumer did not become Ready')
print('PASS deployed graph consumer Ready with own Inbox, scoped dual-topic Kafka identity and Cassandra role',flush=True)
