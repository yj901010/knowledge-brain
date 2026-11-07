"""Install imported Learning/Space or Gateway only; preserve every existing service boundary."""
import argparse,json,re,subprocess,shutil
from pathlib import Path
import yaml
from local_feature_installer_guard import require_tag_baseline
root=Path(__file__).resolve().parents[1];p=argparse.ArgumentParser();p.add_argument('--helm',required=True);p.add_argument('--gateway',action='store_true');p.add_argument('--learning-only',action='store_true');p.add_argument('--content-only',action='store_true');p.add_argument('--knowledge-only',action='store_true');p.add_argument('--media-only',action='store_true');p.add_argument('--activity-only',action='store_true');p.add_argument('--space-only',action='store_true');p.add_argument('--planning-only',action='store_true');p.add_argument('--notification-only',action='store_true');p.add_argument('--brain-query-only',action='store_true');a=p.parse_args()
if sum([a.gateway,a.learning_only,a.content_only,a.knowledge_only,a.media_only,a.activity_only,a.space_only,a.planning_only,a.notification_only,a.brain_query_only])>1:p.error('Choose a single service selector')
require_tag_baseline(__file__, root, vars(a))
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(cmd):
 r=subprocess.run(cmd,text=True,encoding='utf-8',capture_output=True)
 if r.returncode:raise RuntimeError('Roadmap deployment failed: '+r.stderr[:300])
 return r.stdout
assert run(k+['config','current-context']).strip()=='k3d-knowledge-brain';assert Path(a.helm).is_file()
imports=json.loads(run(k+['get','jobs','-n','brain-apps','-o','json']))['items'];nodes={n['metadata']['name'] for n in json.loads(run(k+['get','nodes','-o','json']))['items']}
selected=(['brain-query'] if a.brain_query_only else ['notification'] if a.notification_only else ['planning'] if a.planning_only else None)
for service in (selected or (['gateway'] if a.gateway else (['activity'] if a.activity_only else (['space'] if a.space_only else (['media'] if a.media_only else (['knowledge'] if a.knowledge_only else (['content'] if a.content_only else (['learning'] if a.learning_only else ['space','learning'])))))))):
 meta=json.loads((root/f'.local/{service}-image.json').read_text());assert re.fullmatch(service+'-image-build-[0-9]+',meta['pod']) and re.fullmatch('local-[0-9a-f]{16}',meta['tag']);assert meta['image']=='knowledge-brain/'+service+'-service:'+meta['tag'];url='http://'+meta['pod']+'.brain-apps.svc:9080/image.tar';done={j['spec']['template']['spec'].get('nodeName') for j in imports if j.get('status',{}).get('succeeded') and j['metadata']['name'].startswith(service+'-image-import-') and url in ' '.join(j['spec']['template']['spec']['containers'][0].get('command',[]))};assert nodes and nodes.issubset(done),'Import exact image on every node first'
 path=root/f'infra/environments/local/{service}-values.yaml';v=yaml.safe_load(path.read_text(encoding='utf-8'));v['image']['tag']=meta['tag'];path.write_text(yaml.safe_dump(v,sort_keys=False),encoding='utf-8')
 run([a.helm,'upgrade','--install',service+'-service',str(root/'infra/charts/brain-service'),'-n','brain-apps','-f',str(root/f'infra/services/{service}-values.yaml'),'-f',str(path),'--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--kube-apiserver=https://127.0.0.1:6550']);run(k+['rollout','status','deployment/'+service+'-service','-n','brain-apps','--timeout=900s' if service=='activity' else '--timeout=60s']);print('PASS '+service+' Ready',flush=True)
