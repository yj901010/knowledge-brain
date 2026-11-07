import json,shutil,subprocess,time,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 result=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if result.returncode:raise RuntimeError(result.stderr)
 return result.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
if any(argument!='--restart' for argument in sys.argv[1:]):raise RuntimeError('Supported option: --restart')
name='daily-api-validation-'+str(int(time.time()))
for service in ('content','gateway'):
 run(['rollout','status','deployment/'+service+'-service','-n','brain-apps','--timeout=60s'])
run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-data'),data={'verify.py':(root/'scripts/Verify-DailyApi.py').read_text(encoding='utf-8')})))
job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=480,ttlSecondsAfterFinished=86400,template=dict(metadata=dict(labels={'brain-guest-api-validation':'true'}),spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,fsGroup=10001),containers=[dict(name='verify',image='python:3.13.7-alpine',command=['python','/source/verify.py'],env=[dict(name='BRAIN_TEST_RESTART',value='true' if '--restart' in sys.argv else 'false')],securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='50m',memory='64Mi'),limits=dict(cpu='500m',memory='128Mi')),volumeMounts=[dict(name='source',mountPath='/source',readOnly=True),dict(name='trust',mountPath='/trust',readOnly=True),dict(name='temporary',mountPath='/tmp')])],volumes=[dict(name='source',configMap=dict(name=name)),dict(name='trust',secret=dict(secretName='brain-guest-api-trust')),dict(name='temporary',emptyDir=dict(sizeLimit='16Mi'))]))))
run(['apply','-f','-'],json.dumps(job))
uid=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))['metadata']['uid']
run(['patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':uid}]}})])
print('JOB='+name,flush=True)
if '--restart' in sys.argv:print('After phase 1 PASS, delete the old Content Pod and wait for its deletion; wait until a DIFFERENT Content Pod is Ready (rollout status alone may be stale), then touch /tmp/restart.complete in the verifier Pod')
