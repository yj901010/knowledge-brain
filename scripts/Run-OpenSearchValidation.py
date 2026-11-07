"""Create a confined local OpenSearch validation Job; credentials never leave Secrets."""
import json,shutil,subprocess,sys,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if p.returncode:raise RuntimeError(p.stderr)
 return p.stdout
assert run(['config','current-context']).strip()=='k3d-knowledge-brain','Local context required'
name='opensearch-validation-'+str(int(time.time()));denied='--network-denied' in sys.argv
run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-data'),data={'verify.py':(root/'scripts/Verify-OpenSearch.py').read_text(encoding='utf-8')})))
roles=['reader'] if denied else ['reader','projector']
mounts=[dict(name='source',mountPath='/source',readOnly=True),dict(name='state',mountPath='/state')]+[dict(name=role,mountPath='/search-'+role,readOnly=True) for role in roles]
volumes=[dict(name='source',configMap=dict(name=name)),dict(name='state',emptyDir=dict(sizeLimit='1Mi'))]+[dict(name=role,secret=dict(secretName='brain-search-'+role+'-tls',defaultMode=288)) for role in roles]
job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=180,ttlSecondsAfterFinished=86400,template=dict(metadata=dict(labels={} if denied else {'brain-search-client':'true'}),spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,fsGroup=10001,seccompProfile=dict(type='RuntimeDefault')),containers=[dict(name='verify',image='python:3.13.7-alpine',command=['python','/source/verify.py'],env=[dict(name='NETWORK_DENIED',value=str(denied).lower()),dict(name='RESTART_CHECK',value=str('--restart' in sys.argv).lower())],securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='50m',memory='64Mi'),limits=dict(cpu='250m',memory='128Mi')),volumeMounts=mounts)],volumes=volumes))))
run(['apply','-f','-'],json.dumps(job));uid=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))['metadata']['uid'];run(['patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':uid}]}})])
print('JOB='+name,flush=True)
