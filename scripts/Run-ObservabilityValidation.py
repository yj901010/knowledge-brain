import argparse,json,sys,time
from local_recovery import Cluster,ROOT,write_json,now

def main():
 parser=argparse.ArgumentParser();parser.add_argument('--mode',choices=['canary','acceptance'],default='canary');args=parser.parse_args()
 cluster=Cluster(ROOT/'.local/kubeconfig.yaml','knowledge-brain');name='observability-'+args.mode+'-'+str(int(time.time()))
 cluster.apply([dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-observability'),data={'verify.py':(ROOT/'scripts/Verify-Observability.py').read_text(encoding='utf-8')})])
 mounts=[dict(name='source',mountPath='/source',readOnly=True),dict(name='tls',mountPath='/tls',readOnly=True),dict(name='admin',mountPath='/admin',readOnly=True)]
 volumes=[dict(name='source',configMap=dict(name=name)),dict(name='tls',secret=dict(secretName='brain-telemetry-client')),dict(name='admin',secret=dict(secretName='brain-grafana-admin'))]
 job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-observability'),spec=dict(backoffLimit=0,activeDeadlineSeconds=900,ttlSecondsAfterFinished=86400,template=dict(metadata=dict(labels={'brain-observability-validation':'true','brain-guest-api-validation':'true'}),spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,runAsGroup=10001,seccompProfile=dict(type='RuntimeDefault')),containers=[dict(name='verify',image='python:3.13.7-alpine',command=['python','-u','/source/verify.py'],env=[dict(name='BRAIN_OBSERVABILITY_TEST',value=args.mode)],securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='100m',memory='64Mi'),limits=dict(cpu='500m',memory='128Mi')),volumeMounts=mounts)],volumes=volumes))))
 cluster.apply([job]);uid=cluster.get('job',name,'brain-observability')['metadata']['uid']
 cluster.run('patch','configmap',name,'-n','brain-observability','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[dict(apiVersion='batch/v1',kind='Job',name=name,uid=uid)]}}))
 print('JOB='+name,flush=True);end=time.monotonic()+930
 while time.monotonic()<end:
  status=cluster.get('job',name,'brain-observability').get('status',{})
  if status.get('failed'):
   print(cluster.run('logs','job/'+name,'-n','brain-observability').decode(),flush=True);raise RuntimeError('Observability validation failed')
  if status.get('succeeded'):
   logs=cluster.run('logs','job/'+name,'-n','brain-observability').decode();print(logs,flush=True)
   result=json.loads(logs.split('OBSERVABILITY_VERIFIED=')[1].splitlines()[0]);result.update(job=name,uid=uid,sourceUid=cluster.uid(),verifiedAt=now())
   write_json(ROOT/'.local'/('observability-'+args.mode+'-validation.json'),result);return
  time.sleep(3)
 raise TimeoutError('Observability verifier deadline exceeded')

if __name__=='__main__':sys.stdout.reconfigure(encoding='utf-8');main()
