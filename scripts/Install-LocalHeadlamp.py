"""Install the reviewed, pinned local Headlamp chart without exporting credentials."""
import argparse,hashlib,json,shutil,subprocess,sys,urllib.request
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--helm',help='Path to an installed helm executable if it is not on PATH');args=p.parse_args()
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def command(argv):
 result=subprocess.run(argv,text=True,capture_output=True)
 if result.returncode:raise RuntimeError(result.stderr or 'Command failed')
 return result.stdout
assert command(k+['config','current-context']).strip()=='k3d-knowledge-brain','Local context required'
helm=args.helm or shutil.which('helm')
if not helm or not Path(helm).is_file():raise RuntimeError('Helm required: put it on PATH or provide --helm <executable>')
meta=json.loads((root/'infra/dependencies/headlamp.json').read_text(encoding='utf-8'));cache=root/'.local/headlamp';cache.mkdir(parents=True,exist_ok=True);chart=cache/'headlamp.tgz'
if not chart.exists() or hashlib.sha256(chart.read_bytes()).hexdigest()!=meta['chartSha256']:
 if not meta['chartUrl'].startswith('https://github.com/kubernetes-sigs/headlamp/releases/download/'):raise RuntimeError('Reviewed upstream chart URL required')
 blob=urllib.request.urlopen(meta['chartUrl'],timeout=30).read()
 if hashlib.sha256(blob).hexdigest()!=meta['chartSha256']:raise RuntimeError('Pinned chart digest mismatch')
 chart.write_bytes(blob)
command(k+['apply','-f',str(root/'infra/headlamp/access.yaml')])
print(command([sys.executable,str(root/'scripts/Provision-HeadlampTls.py')]).strip())
command([helm,'upgrade','--install','brain-headlamp',str(chart),'-n','brain-ops','-f',str(root/'infra/environments/local/headlamp-values.yaml'),'--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--kube-apiserver=https://127.0.0.1:6550'])
print(command(k+['rollout','status','deployment/brain-headlamp','-n','brain-ops','--timeout=60s']).strip())
print('Headlamp installed. Run Start-LocalHeadlamp.ps1 and Copy-LocalHeadlampLogin.ps1 for local browser access.')
