"""Prepare additive public topics/ACLs/contracts before enabling any new writer."""
import argparse,json,os,subprocess,time
from local_recovery import Cluster,ROOT,now,write_json
parser=argparse.ArgumentParser();parser.add_argument('--helm',required=True);args=parser.parse_args()
c=Cluster(ROOT/'.local/kubeconfig.yaml','knowledge-brain');uid=c.uid();env=os.environ.copy()
for kind in ['CACHE','CONFIG','DATA']:env['HELM_'+kind+'_HOME']=str(ROOT/f'.local/helm/{kind.lower()}')
subprocess.run([args.helm,'upgrade','--install','brain-connect',str(ROOT/'infra/charts/brain-connect'),'-n','brain-events','--reuse-values','--kubeconfig',str(c.config),'--kube-context','k3d-knowledge-brain','--wait','--timeout','5m'],check=True,env=env,capture_output=True)
for resource in ['kafkatopic/knowledge.public-knowledge.v1','kafkauser/brain-public-kafka','kafkauser/brain-connect','kafkauser/brain-knowledge-validator']:
 c.run('wait',resource,'-n','brain-events','--for=condition=Ready','--timeout=120s',timeout=140)
name='public-discovery-contracts-'+str(int(time.time()))
data={'verify.py':(ROOT/'scripts/Publish-PublicDiscoveryContracts.py').read_text()}
for filename in ['knowledge_public_knowledge_v1.proto']:data[filename]=(ROOT/'contracts/events/src/main/protobuf'/filename).read_text()
c.apply([dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-data'),data=data)])
job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=180,ttlSecondsAfterFinished=86400,template=dict(spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,runAsGroup=10001,seccompProfile=dict(type='RuntimeDefault')),containers=[dict(name='verify',image='python:3.13.7-alpine',command=['python','-u','/scripts/verify.py'],securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='50m',memory='64Mi'),limits=dict(cpu='250m',memory='128Mi')),volumeMounts=[dict(name=n,mountPath='/'+n,readOnly=True) for n in ['scripts','clients','trust']])],volumes=[dict(name='scripts',configMap=dict(name=name)),dict(name='clients',secret=dict(secretName='brain-registry-clients')),dict(name='trust',secret=dict(secretName='brain-keycloak-tls',items=[dict(key='tls.crt',path='tls.crt')]))]))))
c.apply([job]);job_uid=c.get('job',name,'brain-data')['metadata']['uid'];c.run('patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps(dict(metadata=dict(ownerReferences=[dict(apiVersion='batch/v1',kind='Job',name=name,uid=job_uid)]))))
print('JOB='+name,flush=True)
for _ in range(65):
 status=c.get('job',name,'brain-data').get('status',{})
 if status.get('failed'):raise RuntimeError(c.run('logs','job/'+name,'-n','brain-data').decode())
 if status.get('succeeded'):
  print(c.run('logs','job/'+name,'-n','brain-data').decode(),flush=True);write_json(ROOT/'.local/public-discovery-contracts.json',dict(sourceUid=uid,job=name,uid=job_uid,verifiedAt=now()));break
 time.sleep(3)
else:raise TimeoutError('Contract deadline exceeded')
