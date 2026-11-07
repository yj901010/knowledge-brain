"""Install the independently built/imported Brain Query projection service locally."""
import argparse,json,re,shutil,subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
from local_feature_installer_guard import require_tag_baseline
p=argparse.ArgumentParser();p.add_argument('--helm');args=p.parse_args()
require_tag_baseline(__file__, root, vars(args))
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def command(argv):
 result=subprocess.run(argv,text=True,capture_output=True)
 if result.returncode:raise RuntimeError(result.stderr or 'Command failed')
 return result.stdout
if command(k+['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
helm=args.helm or shutil.which('helm')
if not helm or not Path(helm).is_file():raise RuntimeError('Existing Helm executable required')
meta=json.loads((root/'.local/brain-query-image.json').read_text(encoding='utf-8'))
if not re.fullmatch(r'local-[0-9a-f]{16}',meta['tag']) or meta['image']!='knowledge-brain/brain-query-service:'+meta['tag']:raise RuntimeError('Expected local Brain Query image metadata')
command(k+['wait','pod/brain-opensearch-0','-n','brain-data','--for=condition=Ready','--timeout=60s'])
command(k+['apply','-f',str(root/'infra/search/kafka-access.yaml')])
for helper in ['Provision-GuestApiSecrets.py','Provision-QueryProjectorSecrets.py']:
 print(command([sys.executable,str(root/'scripts'/helper)]).strip(),flush=True)
command([helm,'upgrade','--install','brain-query-service',str(root/'infra/charts/brain-service'),'-n','brain-apps','-f',str(root/'infra/services/brain-query-values.yaml'),'-f',str(root/'infra/environments/local/brain-query-values.yaml'),'--set','image.tag='+meta['tag'],'--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--kube-apiserver=https://127.0.0.1:6550'])
command(k+['rollout','status','deployment/brain-query-service','-n','brain-apps','--timeout=60s'])
print('PASS Brain Query projector Ready; Kafka topic read-only, own DB and fixed OpenSearch index')
