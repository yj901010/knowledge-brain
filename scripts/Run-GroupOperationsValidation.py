import json,shutil,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 r=subprocess.run(k+args,input=body,text=True,encoding="utf-8",capture_output=True)
 if r.returncode:raise RuntimeError(r.stderr)
 return r.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
def apply(doc):return run(['apply','-f','-'],json.dumps(doc))
name='group-operations-' +str(int(time.time()))
apply(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-data'),data={'configure.py':(root/'scripts/Verify-GroupOperations.py').read_text(),'collaboration-outbox.json':(root/'infra/connectors/collaboration-outbox.json').read_text()}))
apply(dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=150,ttlSecondsAfterFinished=86400,template=dict(metadata=dict(labels={'brain-connect-admin':'true'}),spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,runAsGroup=10001),containers=[dict(name='configure',image='python:3.13.7-alpine',command=['python','/scripts/configure.py'],securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='50m',memory='64Mi'),limits=dict(cpu='250m',memory='128Mi')),volumeMounts=[dict(name='scripts',mountPath='/scripts',readOnly=True)])],volumes=[dict(name='scripts',configMap=dict(name=name))])))))
job=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))
run(['patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':job['metadata']['uid']}]}})])
print('JOB='+name)
