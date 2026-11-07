"""Deploy imported local artifacts, canonical migrations and scoped event transport."""
import argparse,json,re,shutil,subprocess,sys,time
from pathlib import Path
import yaml
root=Path(__file__).resolve().parents[1]
from local_feature_installer_guard import require_tag_baseline
p=argparse.ArgumentParser();p.add_argument('--helm',required=True);a=p.parse_args()
require_tag_baseline(__file__, root, vars(a))
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(command):
 r=subprocess.run(command,text=True,encoding='utf-8',capture_output=True)
 if r.returncode:raise RuntimeError('Local deployment command failed: '+command[0])
 return r.stdout
if run(k+['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
if not Path(a.helm).is_file():raise RuntimeError('Existing Helm executable required')
def helper(name):return run([sys.executable,str(root/'scripts'/name)])
def job(name):
 output=helper(name);match=re.search(r'JOB=([a-z0-9-]+)',output)
 if not match:raise RuntimeError('Expected owned validation Job')
 value=match.group(1)
 for _ in range(180):
  state=json.loads(run(k+['get','job',value,'-n','brain-data','-o','json'])).get('status',{})
  if state.get('failed'):raise RuntimeError('Job failed: '+value)
  if state.get('succeeded'):print('PASS '+value,flush=True);return
  time.sleep(1)
 raise RuntimeError('Job timeout: '+value)
def deploy(service):
 path=root/f'infra/environments/local/{service}-values.yaml';values=yaml.safe_load(path.read_text());meta=json.loads((root/'.local'/f'{service}-image.json').read_text())
 if not re.fullmatch('local-[0-9a-f]{16}',meta['tag']) or meta['image']!='knowledge-brain/'+service+'-service:'+meta['tag']:raise RuntimeError('Imported local image required')
 values['image']['tag']=meta['tag'];path.write_text(yaml.safe_dump(values,sort_keys=False))
 run([a.helm,'upgrade','--install',service+'-service',str(root/'infra/charts/brain-service'),'-n','brain-apps','-f',str(root/f'infra/services/{service}-values.yaml'),'-f',str(path),'--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--kube-apiserver=https://127.0.0.1:6550'])
 run(k+['rollout','status','deployment/'+service+'-service','-n','brain-apps','--timeout=60s']);print('PASS '+service+' Ready',flush=True)
helper('Provision-GuestApiSecrets.py');deploy('space');deploy('knowledge');job('Provision-KnowledgeCdc.py')
run([a.helm,'upgrade','brain-connect',str(root/'infra/charts/brain-connect'),'-n','brain-events','--reuse-values','-f',str(root/'infra/environments/local/connect-values.yaml'),'--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--kube-apiserver=https://127.0.0.1:6550'])
for topic in ['knowledge.concept.v1','content.note-concepts.v1']:run(k+['wait','kafkatopic/'+topic,'-n','brain-events','--for=condition=Ready','--timeout=60s'])
for user in ['brain-connect','brain-content-validator','brain-knowledge-validator']:run(k+['wait','kafkauser/'+user,'-n','brain-events','--for=condition=Ready','--timeout=60s'])
run(k+['rollout','status','deployment/brain-connect','-n','brain-events','--timeout=60s'])
for name in ['Run-KnowledgeContractValidation.py','Run-NoteConceptContractValidation.py','Run-KnowledgeConnectorConfiguration.py','Run-ContentConnectorConfiguration.py']:job(name)
helper('Provision-KnowledgeValidationClient.py');deploy('content');deploy('gateway')
print('PASS private Note Concept deployment and scoped CDC ready')
