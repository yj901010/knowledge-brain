"""Provision scoped read-only Kafka identity and Knowledge-only canonical snapshot credential."""
import json,base64,secrets,subprocess,shutil
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 r=subprocess.run(k+args,input=body,text=True,encoding='utf-8',capture_output=True)
 if r.returncode:raise RuntimeError('Scoped network provisioning failed')
 return r.stdout
assert run(['config','current-context']).strip()=='k3d-knowledge-brain'
run(['apply','-f',str(root/'infra/graph/network-kafka-access.yaml')]);run(['wait','kafkauser/brain-network-projector','-n','brain-events','--for=condition=Ready','--timeout=60s'])
user=json.loads(run(['get','secret','brain-network-projector','-n','brain-events','-o','json']))['data'];ca=json.loads(run(['get','secret','brain-kafka-cluster-ca-cert','-n','brain-events','-o','json']))['data']
for ns in ['brain-apps','brain-data']:run(['apply','-f','-'],json.dumps({'apiVersion':'v1','kind':'Secret','metadata':{'name':'brain-network-kafka','namespace':ns},'type':'Opaque','data':{'ca.crt':ca['ca.crt'],'user.crt':user['user.crt'],'user.key':user['user.key']}}))
existing=run(['get','secret','brain-knowledge-network-snapshot','-n','brain-apps','--ignore-not-found','-o','json'])
if not existing.strip():run(['apply','-f','-'],json.dumps({'apiVersion':'v1','kind':'Secret','metadata':{'name':'brain-knowledge-network-snapshot','namespace':'brain-apps'},'type':'Opaque','data':{'key':base64.b64encode(secrets.token_urlsafe(32).encode()).decode()}}))
print('PASS nine-topic read-only Kafka identity and private Knowledge snapshot credential; no secret output')
