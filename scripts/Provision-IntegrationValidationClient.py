import json,shutil,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 r=subprocess.run(k+args,input=body,text=True,encoding="utf-8",capture_output=True)
 if r.returncode:raise RuntimeError(r.stderr)
 return r.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
user=json.loads(run(['get','secret','brain-integration-validator','-n','brain-events','-o','json']))['data']
ca=json.loads(run(['get','secret','brain-kafka-cluster-ca-cert','-n','brain-events','-o','json']))['data']
# Strimzi KafkaUser ca.crt is the clients CA, not the brokers' server CA.
secret=dict(apiVersion='v1',kind='Secret',metadata=dict(name='brain-integration-validator',namespace='brain-data'),type='Opaque',data={'ca.crt':ca['ca.crt'],'user.crt':user['user.crt'],'user.key':user['user.key']})
run(['apply','-f','-'],json.dumps(secret))
print('Provisioned readonly event client with broker CA; credential values not printed')
