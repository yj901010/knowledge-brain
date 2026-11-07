import json,shutil,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if p.returncode:raise RuntimeError(p.stderr)
 return p.stdout
assert run(['config','current-context']).strip()=='k3d-knowledge-brain','Local context required'
run(['rollout','status','deployment/brain-headlamp','-n','brain-ops','--timeout=60s'])
name='headlamp-validation-'+str(int(time.time()))
cm=dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-ops'),data={'verify.py':(root/'scripts/Verify-Headlamp.py').read_text(encoding='utf-8')})
run(['apply','-f','-'],json.dumps(cm))
job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-ops'),spec=dict(backoffLimit=0,activeDeadlineSeconds=120,ttlSecondsAfterFinished=86400,template=dict(metadata=dict(labels={'headlamp-validation':'true'}),spec=dict(serviceAccountName='brain-headlamp-viewer',automountServiceAccountToken=True,restartPolicy='Never',securityContext=dict(runAsNonRoot=True,runAsUser=10001,fsGroup=10001,seccompProfile=dict(type='RuntimeDefault')),containers=[dict(name='verify',image='python:3.13.7-alpine',command=['python','/source/verify.py'],securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='50m',memory='64Mi'),limits=dict(cpu='250m',memory='128Mi')),volumeMounts=[dict(name='source',mountPath='/source',readOnly=True),dict(name='trust',mountPath='/trust',readOnly=True)])],volumes=[dict(name='source',configMap=dict(name=name)),dict(name='trust',secret=dict(secretName='brain-headlamp-trust'))]))))
run(['apply','-f','-'],json.dumps(job));uid=json.loads(run(['get','job',name,'-n','brain-ops','-o','json']))['metadata']['uid'];run(['patch','configmap',name,'-n','brain-ops','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':uid}]}})])
print('JOB='+name,flush=True)
