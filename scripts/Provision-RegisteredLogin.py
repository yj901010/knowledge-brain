"""Provision local login credentials and a narrowly scoped, temporary Realm bootstrap."""
import base64,json,secrets,shutil,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 r=subprocess.run(k+args,input=body,text=True,encoding='utf-8',capture_output=True)
 if r.returncode:raise RuntimeError('Local login provisioning command failed')
 return r.stdout
def apply(doc):run(['apply','--server-side','-f','-'],json.dumps(doc))
assert run(['config','current-context']).strip()=='k3d-knowledge-brain','Local context required'
existing=json.loads(run(['get','secrets','-n','brain-apps','-o','json']))
secret=next((s for s in existing['items'] if s['metadata']['name']=='brain-user-login'),None)
data=secret['data'] if secret else {key:base64.b64encode(secrets.token_urlsafe(32).encode()).decode() for key in ['client-secret','encryption-key']}
for namespace in ['brain-apps','brain-data']:
 apply(dict(apiVersion='v1',kind='Secret',metadata=dict(name='brain-user-login',namespace=namespace),type='Opaque',data=data if namespace=='brain-apps' else {'client-secret':data['client-secret']}))
cert=json.loads(run(['get','secret','brain-keycloak-tls','-n','brain-data','-o','json']))['data']['tls.crt']
apply(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name='brain-user-login-trust',namespace='brain-apps'),binaryData={'tls.crt':cert}))
(root/'.local/keycloak-ca.crt').write_bytes(base64.b64decode(cert))
name='user-realm-bootstrap-'+str(int(time.time()))
apply(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-data'),data={'configure.py':(root/'scripts/Configure-UserRealm.py').read_text(encoding='utf-8'),'realm.json':(root/'infra/charts/brain-keycloak/files/brain-users-realm.json').read_text(encoding='utf-8')}))
mounts=[dict(name=n,mountPath='/'+n,readOnly=True) for n in ['scripts','trust','admin','login']]
volumes=[dict(name='scripts',configMap=dict(name=name)),dict(name='trust',secret=dict(secretName='brain-keycloak-tls',items=[dict(key='tls.crt',path='tls.crt')])),dict(name='admin',secret=dict(secretName='brain-keycloak-admin')),dict(name='login',secret=dict(secretName='brain-user-login',items=[dict(key='client-secret',path='client-secret')]))]
apply(dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=180,ttlSecondsAfterFinished=3600,template=dict(spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,runAsGroup=10001),containers=[dict(name='configure',image='python:3.13.7-alpine',command=['python','/scripts/configure.py'],env=[dict(name='PYTHONDONTWRITEBYTECODE',value='1')],securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='50m',memory='64Mi'),limits=dict(cpu='250m',memory='128Mi')),volumeMounts=mounts)],volumes=volumes)))))
print('JOB='+name,flush=True)
