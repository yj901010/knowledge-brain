"""Prepare the dedicated local Collaboration-to-Media credential; preserve an existing key."""
import json,secrets,shutil,subprocess
from pathlib import Path
r=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(r/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,encoding='utf-8',capture_output=True)
 if p.returncode:raise RuntimeError(p.stderr)
 return p.stdout
assert run(['config','current-context']).strip()=='k3d-knowledge-brain'
existing=run(['get','secret','brain-collaboration-media-authorization','-n','brain-apps','--ignore-not-found','-o','json'])
if existing:
 import base64,re
 value=json.loads(existing);key=base64.b64decode(value['data']['key']).decode();assert re.fullmatch('[A-Za-z0-9_-]{43}',key)
else:
 run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='Secret',metadata=dict(name='brain-collaboration-media-authorization',namespace='brain-apps'),type='Opaque',stringData=dict(key=secrets.token_urlsafe(32)))))
print('PASS dedicated opaque-file credential exists; no credentials printed')
