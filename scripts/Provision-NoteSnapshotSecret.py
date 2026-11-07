"""Local-only Content snapshot credential; never export or log the credential."""
import base64,json,secrets,shutil,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if p.returncode:raise RuntimeError('Snapshot secret provisioning failed')
 return p.stdout
assert run(['config','current-context']).strip()=='k3d-knowledge-brain','Local context required'
existing=json.loads(run(['get','secret','brain-content-search-snapshot','-n','brain-apps','--ignore-not-found','-o','json']) or '{}')
data=existing.get('data') or {'key':base64.b64encode(secrets.token_urlsafe(32).encode()).decode()}
run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='Secret',metadata=dict(name='brain-content-search-snapshot',namespace='brain-apps'),type='Opaque',data=data)))
print('PASS scoped Content snapshot secret provisioned in brain-apps')
