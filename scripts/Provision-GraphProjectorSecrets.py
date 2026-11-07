"""Provision only graph topic/group mTLS and the own-keyspace Cassandra role; no operator credentials copied."""
import json,shutil,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if p.returncode:raise RuntimeError('Graph secret provisioning command failed')
 return p.stdout
def get(name,ns):return json.loads(run(['get','secret',name,'-n',ns,'-o','json']))['data']
def put(name,ns,data):run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='Secret',metadata=dict(name=name,namespace=ns),type='Opaque',data=data)))
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
run(['apply','-f',str(root/'infra/graph/kafka-access.yaml')]);run(['wait','kafkauser/brain-graph-projector','-n','brain-events','--for=condition=Ready','--timeout=45s'])
user=get('brain-graph-projector','brain-events');broker=get('brain-kafka-cluster-ca-cert','brain-events')
for ns in ['brain-apps','brain-data']:put('brain-graph-kafka',ns,{'ca.crt':broker['ca.crt'],'user.crt':user['user.crt'],'user.key':user['user.key']})
put('brain-graph-projector','brain-apps',get('brain-graph-projector','brain-data'))
print('PASS graph read-only topic/group mTLS and own-keyspace role provisioned; no graph admin credentials exported')
