"""Launch a private TLS Cassandra bootstrap or scoped role validation Job."""
import argparse,json,shutil,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--bootstrap',action='store_true');p.add_argument('--restart',action='store_true');p.add_argument('--network-denied',action='store_true');a=p.parse_args()
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if p.returncode:raise RuntimeError('Cassandra Job operation failed')
 return p.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
name='cassandra-'+('bootstrap' if a.bootstrap else 'validation')+'-'+str(int(time.time()))
run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-data'),data={'verify.py':(root/'scripts/Verify-Cassandra.py').read_text(encoding='utf-8')})))
mounts=[dict(name='source',mountPath='/source',readOnly=True),dict(name='tmp',mountPath='/tmp')]+[dict(name=role,mountPath='/'+role,readOnly=True) for role in ['admin','projector']]
volumes=[dict(name='source',configMap=dict(name=name)),dict(name='tmp',emptyDir=dict(sizeLimit='32Mi'))]+[dict(name=role,secret=dict(secretName='brain-graph-'+role)) for role in ['admin','projector']]
image=json.loads((root/'infra/dependencies/cassandra.json').read_text())['image']
if a.network_denied:
 mounts=[dict(name='source',mountPath='/source',readOnly=True),dict(name='tmp',mountPath='/tmp'),dict(name='public-trust',mountPath='/admin',readOnly=True)]
 volumes=[dict(name='source',configMap=dict(name=name)),dict(name='tmp',emptyDir=dict(sizeLimit='32Mi')),dict(name='public-trust',secret=dict(secretName='brain-guest-api-trust'))]
job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=240,ttlSecondsAfterFinished=86400,template=dict(metadata=dict(labels={} if a.network_denied else {'brain-graph-client':'true'}),spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=999,fsGroup=999,seccompProfile=dict(type='RuntimeDefault')),containers=[dict(name='verify',image=image,command=['python3','/source/verify.py'],env=[dict(name='MODE',value='network-denied' if a.network_denied else 'bootstrap' if a.bootstrap else 'validate'),dict(name='RESTART_CHECK',value=str(a.restart).lower()),dict(name='HOME',value='/tmp')],securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='50m',memory='128Mi'),limits=dict(cpu='500m',memory='256Mi')),volumeMounts=mounts)],volumes=volumes))))
run(['apply','-f','-'],json.dumps(job));uid=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))['metadata']['uid']
run(['patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':uid}]}})])
print('JOB='+name,flush=True)
