"""Copy only Brain Query's DB password and scoped Kafka identity; never print credentials."""
import json,shutil,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if p.returncode:raise RuntimeError(p.stderr)
 return p.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
def secret(name,ns):return json.loads(run(['get','secret',name,'-n',ns,'-o','json']))['data']
def apply(name,ns,data):run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='Secret',metadata=dict(name=name,namespace=ns),type='Opaque',data=data)))
run(['wait','kafkauser/brain-query-projector','-n','brain-events','--for=condition=Ready','--timeout=45s'])
identity=secret('brain-query-projector','brain-events');broker=secret('brain-kafka-cluster-ca-cert','brain-events')
data={'ca.crt':broker['ca.crt'],'user.crt':identity['user.crt'],'user.key':identity['user.key']}
apply('brain-query-kafka','brain-apps',data)
apply('brain-query-kafka','brain-data',data)
apply('brain-brainquery-db','brain-apps',{'password':secret('brain-brainquery-db','brain-data')['password']})
print('Provisioned Brain Query own DB and read-only Kafka topic/group credentials')
