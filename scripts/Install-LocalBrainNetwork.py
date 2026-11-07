"""Deploy imported network source/projector images; enable only after canonical rebuild."""
import argparse,json,re,shutil,subprocess
from pathlib import Path
import yaml
root=Path(__file__).resolve().parents[1]
from local_feature_installer_guard import require_tag_baseline
p=argparse.ArgumentParser();p.add_argument('--helm',required=True);p.add_argument('--enable',action='store_true');p.add_argument('--gateway',action='store_true');a=p.parse_args()
require_tag_baseline(__file__, root, vars(a))
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(cmd):
 r=subprocess.run(cmd,text=True,encoding='utf-8',capture_output=True)
 if r.returncode:raise RuntimeError('Network deployment failed: '+cmd[0]+': '+r.stderr[:500])
 return r.stdout
assert run(k+['config','current-context']).strip()=='k3d-knowledge-brain'
assert Path(a.helm).is_file()
services=['gateway'] if a.gateway else ['brain-query'] if a.enable else ['space','knowledge','content','brain-query']
imports=json.loads(run(k+['get','jobs','-n','brain-apps','-o','json']))['items'];nodes={n['metadata']['name'] for n in json.loads(run(k+['get','nodes','-o','json']))['items']}
for service in services:
 meta=json.loads((root/'.local'/f'{service}-image.json').read_text())
 assert re.fullmatch(service+'-image-build-[0-9]+',meta['pod']) and re.fullmatch('local-[0-9a-f]{16}',meta['tag'])
 assert meta['image']=='knowledge-brain/'+service+'-service:'+meta['tag']
 url='http://'+meta['pod']+'.brain-apps.svc:9080/image.tar'
 imported={j['spec']['template']['spec'].get('nodeName') for j in imports if j['metadata']['name'].startswith(service+'-image-import-') and j.get('status',{}).get('succeeded') and url in ' '.join(j['spec']['template']['spec']['containers'][0].get('command',[]))}
 assert nodes and nodes.issubset(imported),'Complete exact image imports on each node first: '+service
 if a.enable:assert json.loads((root/'.local/brain-network-rebuild-state.json').read_text())['state']=='complete','Canonical network rebuild must succeed first'
 path=root/f'infra/environments/local/{service}-values.yaml';v=yaml.safe_load(path.read_text(encoding='utf-8'));v['image']['tag']=meta['tag']
 if service=='brain-query':
  for e in v['env']:
   if e['name']=='BRAIN_QUERY_NETWORK_ENABLED':e['value']=str(a.enable).lower()
   if e['name']=='SPRING_PROFILES_ACTIVE':e['value']='projector,graph-projector'+(',network-projector' if a.enable else '')
 path.write_text(yaml.safe_dump(v,sort_keys=False),encoding='utf-8')
 run([a.helm,'upgrade','--install',service+'-service',str(root/'infra/charts/brain-service'),'-n','brain-apps','-f',str(root/f'infra/services/{service}-values.yaml'),'-f',str(path),'--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--kube-apiserver=https://127.0.0.1:6550'])
 run(k+['rollout','status','deployment/'+service+'-service','-n','brain-apps','--timeout=60s']);print('PASS '+service+' Ready',flush=True)
