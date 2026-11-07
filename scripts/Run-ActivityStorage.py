import json,time,subprocess
from pathlib import Path
r=Path(__file__).resolve().parents[1];k=['C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(r/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550'];name='activity-storage-bootstrap-'+str(int(time.time()))
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,encoding='utf-8',capture_output=True)
 if p.returncode:raise RuntimeError('Activity storage validation failed')
 return p.stdout
assert run(['config','current-context']).strip()=='k3d-knowledge-brain'
def apply(v):run(['apply','-f','-'],json.dumps(v))
apply(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-data'),data={'bootstrap.py':(r/'scripts/Bootstrap-ActivityStorage.py').read_text(),'schema.sql':(r/'infra/activity/clickhouse-schema.sql').read_text()}))
mounts=[dict(name='scripts',mountPath='/scripts',readOnly=True),dict(name='trust',mountPath='/trust',readOnly=True)];volumes=[dict(name='scripts',configMap=dict(name=name)),dict(name='trust',secret=dict(secretName='brain-guest-api-trust'))]
for role in ['admin','reader','projector']:
 mounts.append(dict(name=role,mountPath='/'+role,readOnly=True));volumes.append(dict(name=role,secret=dict(secretName='brain-clickhouse-admin' if role=='admin' else 'brain-activity-clickhouse-'+role)))
apply(dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=180,ttlSecondsAfterFinished=86400,template=dict(metadata=dict(labels={'brain-activity-validation':'true'}),spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,runAsGroup=10001),containers=[dict(name='verify',image='python:3.13.7-alpine',command=['python','-u','/scripts/bootstrap.py'],securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='50m',memory='64Mi'),limits=dict(cpu='250m',memory='128Mi')),volumeMounts=mounts)],volumes=volumes)))))
uid=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))['metadata']['uid'];run(['patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps(dict(metadata=dict(ownerReferences=[dict(apiVersion='batch/v1',kind='Job',name=name,uid=uid)])))])
print('JOB='+name)
