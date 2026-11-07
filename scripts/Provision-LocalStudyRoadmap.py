"""Preserve the local Collaboration-to-Learning reference capability without printing it."""
import base64,json,secrets,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=['C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,encoding='utf-8',capture_output=True)
 if p.returncode:raise RuntimeError('Study roadmap capability provisioning failed')
 return p.stdout
assert run(['config','current-context']).strip()=='k3d-knowledge-brain'
name='brain-collaboration-learning-reference';raw=run(['get','secret',name,'-n','brain-apps','--ignore-not-found','-o','json']);data=json.loads(raw)['data'] if raw.strip() else {'key':base64.b64encode(secrets.token_urlsafe(32).encode()).decode()}
run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='Secret',metadata=dict(name=name,namespace='brain-apps'),type='Opaque',data=data)))
print('PASS dedicated Collaboration/Learning reference capability preserved; no secret printed')
