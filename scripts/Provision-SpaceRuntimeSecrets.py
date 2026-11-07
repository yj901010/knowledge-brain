import json,shutil,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 r=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if r.returncode:raise RuntimeError(r.stderr)
 return r.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
def secret(name,namespace):return json.loads(run(['get','secret',name,'-n',namespace,'-o','json']))['data']
ca=secret('brain-kafka-cluster-ca-cert','brain-events');user=secret('brain-space','brain-events')
values=[('brain-apps','brain-space-db',{'password':secret('brain-space-db','brain-data')['password']}),('brain-apps','brain-space-kafka',{'ca.crt':ca['ca.crt'],'user.crt':user['user.crt'],'user.key':user['user.key']})]
validator=secret('brain-space-validator','brain-events')
values.append(('brain-data','brain-space-validator',{'ca.crt':ca['ca.crt'],'user.crt':validator['user.crt'],'user.key':validator['user.key']}))
for ns,name,data in values:run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='Secret',metadata=dict(name=name,namespace=ns),type='Opaque',data=data)))
print('Provisioned owning Space DB and scoped mTLS credentials; values not printed')
