import base64,json,secrets,shutil,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 r=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if r.returncode: raise RuntimeError(r.stderr)
 return r.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
def apply(doc):return run(['apply','-f','-'],json.dumps(doc))
existing=json.loads(run(['get','secret','brain-identity-cdc','-n','brain-data','--ignore-not-found','-o','json']) or 'null')
password=base64.b64decode(existing['data']['password']).decode() if existing else secrets.token_hex(32)
for ns,data in [('brain-data',{'password':password}),('brain-events',{'identity.properties':'password='+password+'\n'})]:
 apply(dict(apiVersion='v1',kind='Secret',metadata=dict(name='brain-identity-cdc',namespace=ns),type='Opaque',stringData=data))
name='identity-cdc-bootstrap-'+str(int(time.time()))
apply(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-data'),data={'bootstrap.sh':(root/'scripts/Bootstrap-IdentityCdc.sh').read_text()}))
env=[]
for variable,secret in [('ADMIN_PASSWORD','brain-postgres-admin'),('OWNER_PASSWORD','brain-identity-db'),('CDC_PASSWORD','brain-identity-cdc')]:
 env.append(dict(name=variable,valueFrom=dict(secretKeyRef=dict(name=secret,key='password'))))
apply(dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=180,ttlSecondsAfterFinished=86400,template=dict(spec=dict(restartPolicy='Never',automountServiceAccountToken=False,containers=[dict(name='bootstrap',image='postgres:17.11-bookworm',command=['bash','/scripts/bootstrap.sh'],env=env,resources=dict(requests=dict(cpu='50m',memory='64Mi'),limits=dict(cpu='250m',memory='128Mi')),volumeMounts=[dict(name='scripts',mountPath='/scripts',readOnly=True)])],volumes=[dict(name='scripts',configMap=dict(name=name))])))))
job=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))
run(['patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':job['metadata']['uid']}]}})])
print('JOB='+name)
