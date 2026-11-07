"""Deploy exact imported public Roadmap artifacts only after topic/contract readiness."""
import argparse,json,re,shutil,subprocess,sys,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
from local_feature_installer_guard import require_tag_baseline
p=argparse.ArgumentParser();p.add_argument('--helm',required=True);a=p.parse_args()
require_tag_baseline(__file__, root, vars(a))
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(command):
 result=subprocess.run(command,text=True,encoding='utf-8',capture_output=True)
 if result.returncode:raise RuntimeError('Public roadmap deployment command failed: '+result.stderr[:300])
 return result.stdout
assert run(k+['config','current-context']).strip()=='k3d-knowledge-brain'
assert Path(a.helm).is_file()
nodes={n['metadata']['name'] for n in json.loads(run(k+['get','nodes','-o','json']))['items']}
imports=json.loads(run(k+['get','jobs','-n','brain-apps','-o','json']))['items']
for service in ['space','learning','gateway']:
 m=json.loads((root/f'.local/{service}-image.json').read_text());assert re.fullmatch(service+'-image-build-[0-9]+',m['pod']) and re.fullmatch('local-[0-9a-f]{16}',m['tag']) and m['image']=='knowledge-brain/'+service+'-service:'+m['tag']
 url='http://'+m['pod']+'.brain-apps.svc:9080/image.tar'
 done={j['spec']['template']['spec'].get('nodeName') for j in imports if j.get('status',{}).get('succeeded') and j['metadata']['name'].startswith(service+'-image-import-') and url in ' '.join(j['spec']['template']['spec']['containers'][0].get('command',[]))}
 assert nodes and nodes.issubset(done),'Import exact '+service+' image onto all nodes first'
run([a.helm,'upgrade','--install','brain-connect',str(root/'infra/charts/brain-connect'),'-n','brain-events','-f',str(root/'infra/environments/local/connect-values.yaml'),'--reuse-values','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--kube-apiserver=https://127.0.0.1:6550'])
for resource in ['kafkatopic/learning.public-roadmap.v1','kafkatopic/learning.roadmap-contribution.v1','kafkauser/brain-connect','kafkauser/brain-learning-validator']:run(k+['wait',resource,'-n','brain-events','--for=condition=Ready','--timeout=60s'])
run(k+['rollout','status','deployment/brain-registry','-n','brain-events','--timeout=120s'])
for contract in ['PublicRoadmap','RoadmapContribution']:
 output=run([sys.executable,str(root/f'scripts/Run-{contract}ContractValidation.py')]);m=re.search(r'JOB=([a-z0-9-]+)',output);assert m
 name=m.group(1)
 for _ in range(180):
  status=json.loads(run(k+['get','job',name,'-n','brain-data','-o','json'])).get('status',{})
  if status.get('failed'):raise RuntimeError('Contract prerequisite failed: '+name)
  if status.get('succeeded'):print('PASS '+name,flush=True);break
  time.sleep(1)
 else:raise RuntimeError('Contract readiness timeout')
for service in ['space','learning','gateway']:
 selector='--gateway' if service=='gateway' else '--'+service+'-only'
 print(run([sys.executable,str(root/'scripts/Install-LocalRoadmaps.py'),'--helm',a.helm,selector]).strip(),flush=True)
print('PASS public roadmap prerequisite contracts and exact imported service images Ready')
