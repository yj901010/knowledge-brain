"""Deploy private Sources through Knowledge and the static Gateway UI."""
import argparse,json,re,shutil,subprocess
from pathlib import Path
import yaml
root=Path(__file__).resolve().parents[1]
from local_feature_installer_guard import require_tag_baseline
p=argparse.ArgumentParser();p.add_argument('--helm',required=True);a=p.parse_args()
require_tag_baseline(__file__, root, vars(a))
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(command):
 r=subprocess.run(command,text=True,encoding='utf-8',capture_output=True)
 if r.returncode:raise RuntimeError('Local Source deployment command failed: '+command[0])
 return r.stdout
assert run(k+['config','current-context']).strip()=='k3d-knowledge-brain'
assert Path(a.helm).is_file(),'Existing Helm executable required'
def helm(release,chart,namespace,values,extra=[]):
 command=[a.helm,'upgrade','--install',release,str(root/'infra/charts'/chart),'-n',namespace]+extra
 for value in values:command+=['-f',str(root/value)]
 run(command+['--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--kube-apiserver=https://127.0.0.1:6550'])
def deploy(service):
 metadata=json.loads((root/'.local'/f'{service}-image.json').read_text(encoding='utf-8'))
 if not re.fullmatch('local-[0-9a-f]{16}',metadata['tag']) or metadata['image']!='knowledge-brain/'+service+'-service:'+metadata['tag']:raise RuntimeError('Imported local image required')
 path=root/f'infra/environments/local/{service}-values.yaml';values=yaml.safe_load(path.read_text(encoding='utf-8'));values['image']['tag']=metadata['tag'];path.write_text(yaml.safe_dump(values,sort_keys=False),encoding='utf-8')
 helm(service+'-service','brain-service','brain-apps',[f'infra/services/{service}-values.yaml',f'infra/environments/local/{service}-values.yaml'])
 run(k+['rollout','status','deployment/'+service+'-service','-n','brain-apps','--timeout=60s']);print('PASS '+service+' Ready',flush=True)
# A build metadata file alone is not proof that the image reached each node.
imports=json.loads(run(k+['get','jobs','-n','brain-apps','-o','json']))['items']
nodes={n['metadata']['name'] for n in json.loads(run(k+['get','nodes','-o','json']))['items']}
for service in ['knowledge','gateway']:
 meta=json.loads((root/'.local'/f'{service}-image.json').read_text(encoding='utf-8'))
 if not re.fullmatch(service+'-image-build-[0-9]+',meta['pod']):raise RuntimeError('Expected owned image build metadata')
 url='http://'+meta['pod']+'.brain-apps.svc:9080/image.tar'
 imported={j['spec']['template']['spec'].get('nodeName') for j in imports if j['metadata']['name'].startswith(service+'-image-import-') and j.get('status',{}).get('succeeded') and url in ' '.join(j['spec']['template']['spec']['containers'][0].get('command',[]))}
 if not nodes or not nodes.issubset(imported):raise RuntimeError('Complete image imports on every local node before deployment: '+service)
for service in ['knowledge','gateway']:deploy(service)
print('PASS personal Sources deployed; Knowledge-owned V5 migration and independent Source V1 events')


