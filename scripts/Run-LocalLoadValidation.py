"""Run the bounded private local workload, bound to current sources and app images."""
import argparse,hashlib,json,sys,time
from local_recovery import Cluster,ROOT,now,write_json

def main():
 p=argparse.ArgumentParser();p.add_argument('--seconds',type=int,default=600);a=p.parse_args();assert 60<=a.seconds<=900
 c=Cluster(ROOT/'.local/kubeconfig.yaml','knowledge-brain');uid=c.uid();name='operations-load-'+str(time.time_ns());source=(ROOT/'scripts/Verify-LocalLoad.py').read_text(encoding='utf-8')
 images={d['metadata']['name']:d['spec']['template']['spec']['containers'][0]['image'] for d in c.get('deployments',namespace='brain-apps')['items']}
 c.apply([dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-observability'),data={'verify.py':source})])
 pod=dict(metadata=dict(labels={'brain-observability-validation':'true','brain-guest-api-validation':'true'}),spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,runAsGroup=10001,seccompProfile=dict(type='RuntimeDefault')),containers=[dict(name='verify',image='python:3.13.7-alpine@sha256:9ba6d8cbebf0fb6546ae71f2a1c14f6ffd2fdab83af7fa5669734ef30ad48844',command=['python','-u','/source/verify.py'],env=[dict(name='BRAIN_LOAD_SECONDS',value=str(a.seconds))],securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='100m',memory='64Mi'),limits=dict(cpu='1',memory='128Mi')),volumeMounts=[dict(name='source',mountPath='/source',readOnly=True),dict(name='trust',mountPath='/trust',readOnly=True)])],volumes=[dict(name='source',configMap=dict(name=name)),dict(name='trust',secret=dict(secretName='brain-telemetry-client',items=[dict(key='ca.crt',path='ca.crt')]))]))
 c.apply([dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-observability',annotations={'brain.knowledge/source-sha256':hashlib.sha256(source.encode()).hexdigest(),'brain.knowledge/cluster-uid':uid}),spec=dict(backoffLimit=0,activeDeadlineSeconds=a.seconds+300,ttlSecondsAfterFinished=86400,template=pod))])
 job=c.get('job',name,'brain-observability');job_uid=job['metadata']['uid'];c.run('patch','configmap',name,'-n','brain-observability','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[dict(apiVersion='batch/v1',kind='Job',name=name,uid=job_uid)]}}))
 directory=ROOT/'.local/operations-load'/name;directory.mkdir(parents=True);write_json(directory/'receipt.json',dict(job=name,jobUid=job_uid,clusterUid=uid,images=images,sourceSha256=hashlib.sha256(source.encode()).hexdigest(),startedAt=now()));print('JOB='+name,flush=True)
 deadline=time.monotonic()+a.seconds+330
 while time.monotonic()<deadline:
  status=c.get('job',name,'brain-observability').get('status',{})
  if status.get('succeeded') or status.get('failed'):break
  time.sleep(5)
 else:raise TimeoutError('Inspect the same live Job; observation deadline is not a restart request')
 logs=c.run('logs','job/'+name,'-n','brain-observability').decode();(directory/'validation.log').write_text(logs,encoding='utf-8')
 final=next((line.split('=',1)[1] for line in logs.splitlines() if line.startswith('LOAD_VERIFIED=')),None)
 if final:write_json(directory/'result.json',json.loads(final))
 assert c.uid()==uid and {d['metadata']['name']:d['spec']['template']['spec']['containers'][0]['image'] for d in c.get('deployments',namespace='brain-apps')['items']}==images
 write_json(ROOT/'.local/operations-load/latest.json',dict(directory=str(directory),job=name,jobUid=job_uid,completed=bool(status.get('succeeded')),verifiedAt=now()))
 assert status.get('succeeded')==1,'Workload failed; inspect retained sanitized Job log'
 print('PASS bounded sustained API workload; evidence='+str(directory),flush=True)

if __name__=='__main__':sys.stdout.reconfigure(encoding='utf-8');main()
