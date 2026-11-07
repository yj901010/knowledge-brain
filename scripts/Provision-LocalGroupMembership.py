"""Preserve a Space-only invitation code derivation key. Never print credentials."""
import base64,json,secrets,subprocess
from pathlib import Path
r=Path(__file__).resolve().parents[1]
k=['C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(r/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,encoding='utf-8',capture_output=True)
 if p.returncode:raise RuntimeError('Local Group invitation key provisioning failed')
 return p.stdout
assert run(['config','current-context']).strip()=='k3d-knowledge-brain'
name='brain-group-invitation-key';raw=run(['get','secret',name,'-n','brain-apps','--ignore-not-found','-o','json'])
data=json.loads(raw)['data'] if raw.strip() else {'key':base64.b64encode(secrets.token_urlsafe(32).encode()).decode()}
run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='Secret',metadata=dict(name=name,namespace='brain-apps'),type='Opaque',data=data)))
print('PASS preserved Space-only invitation code key; no values printed')
