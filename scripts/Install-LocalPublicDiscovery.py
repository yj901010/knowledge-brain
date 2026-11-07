"""Build/import/roll Space, Knowledge, Brain Query, Gateway sequentially; preserve telemetry.
Evidence precedes cleanup of exact owned ephemeral builders. No PVC/offset reset.
"""
import argparse,base64,hashlib,json,os,subprocess,sys,time
from pathlib import Path
import yaml
from local_recovery import Cluster,ROOT,now,write_json
from local_feature_installer_guard import require_tag_baseline
p=argparse.ArgumentParser();p.add_argument('--helm',required=True);p.add_argument('--space-builder');p.add_argument('--refresh-service',choices=['space','knowledge','brain-query','gateway']);p.add_argument('--refresh-services',nargs='+',choices=['space','knowledge','brain-query','gateway'],default=[]);a=p.parse_args()
require_tag_baseline(__file__, ROOT, vars(a))
sys.stdout.reconfigure(encoding='utf-8')
c=Cluster(ROOT/'.local/kubeconfig.yaml','knowledge-brain');uid=c.uid();proof_path=ROOT/'.local/public-discovery-deployment.json';proof=dict(sourceUid=uid,services={},startedAt=now())
if proof_path.exists():
 proof=json.loads(proof_path.read_text(encoding='utf-8'));assert proof['sourceUid']==uid
for service in a.refresh_services+([a.refresh_service] if a.refresh_service else []):proof['services'].pop(service,None)
assert json.loads((ROOT/'.local/public-discovery-contracts.json').read_text())['sourceUid']==uid
env=os.environ.copy()
for kind in ['CACHE','CONFIG','DATA']:env['HELM_'+kind+'_HOME']=str(ROOT/f'.local/helm/{kind.lower()}')
def run(args,timeout=900):
 result=subprocess.run([str(v) for v in args],capture_output=True,encoding='utf-8',env=env,timeout=timeout)
 if result.returncode:raise RuntimeError('Local workbench command failed: '+str(args[0])+'\n'+result.stderr[-1600:]+'\n'+result.stdout[-1200:])
 return result.stdout
for service in ['space','knowledge','brain-query','gateway']:
 if proof['services'].get(service,{}).get('state')=='COMPLETE':continue
 before=c.get('deployment',service+'-service','brain-apps');print('BUILD_AND_ROLL='+service,flush=True)
 metadata_path=ROOT/'.local'/f'{service}-image.json'
 if service=='space' and a.space_builder:
  metadata=json.loads(metadata_path.read_text());assert metadata['pod']==a.space_builder
 else:
  print(run([sys.executable,ROOT/'scripts/Build-LocalSpaceImage.py','--service',service]),flush=True)
  metadata=json.loads(metadata_path.read_text())
 name=metadata['pod'];assert name.startswith(service+'-image-build-')
 deadline=time.monotonic()+900
 while time.monotonic()<deadline:
  pod=c.get('pod',name,'brain-apps');init=pod.get('status',{}).get('initContainerStatuses',[])
  if init and init[0].get('state',{}).get('terminated',{}).get('exitCode',0)!=0:raise RuntimeError('Builder failed: '+name)
  if any(v.get('type')=='Ready' and v.get('status')=='True' for v in pod.get('status',{}).get('conditions',[])):break
  time.sleep(3)
 else:raise TimeoutError('Builder deadline: '+name)
 assert c.uid()==uid
 directory=ROOT/'.local/public-discovery-builder-evidence';directory.mkdir(exist_ok=True)
 maps={};encoded=[]
 for cm_name in metadata['sourceConfigMaps']:
  cm=c.get('configmap',cm_name,'brain-apps');assert any(o['kind']=='Pod' and o['uid']==pod['metadata']['uid'] for o in cm['metadata']['ownerReferences'])
  maps[cm_name]=dict(uid=cm['metadata']['uid'],sha256=hashlib.sha256(json.dumps(cm['data'],sort_keys=True).encode()).hexdigest())
  encoded.extend(cm['data'][key] for key in sorted(cm['data']))
 archive=base64.b64decode(''.join(encoded),validate=True);source_path=directory/(name+'.tar.gz');source_path.write_bytes(archive)
 logs=c.run('logs',name,'-n','brain-apps','-c','build').decode();(directory/(name+'.log')).write_text(logs,encoding='utf-8');assert 'BUILD SUCCESS' in logs
 image_sha=c.run('exec',name,'-n','brain-apps','-c','artifacts','--','cat','/artifacts/image.sha256').decode().strip();assert len(image_sha)==64
 print(run([sys.executable,ROOT/'scripts/Import-LocalSpaceImage.py','--service',service]),flush=True)
 endpoint='http://'+name+'.brain-apps.svc:9080/image.tar';imports=[];nodes={n['metadata']['name'] for n in c.get('nodes')['items']}
 for job in c.get('jobs',namespace='brain-apps')['items']:
  if job['metadata']['name'].startswith(service+'-image-import-') and endpoint in ' '.join(job['spec']['template']['spec']['containers'][0]['command']):
   c.run('wait','job/'+job['metadata']['name'],'-n','brain-apps','--for=condition=Complete','--timeout=180s',timeout=200)
   job=c.get('job',job['metadata']['name'],'brain-apps');import_log=c.run('logs','job/'+job['metadata']['name'],'-n','brain-apps').decode();assert '/tmp/image.tar: OK' in import_log
   imports.append(dict(name=job['metadata']['name'],uid=job['metadata']['uid'],node=job['spec']['template']['spec']['nodeName'],log=import_log))
 assert {v['node'] for v in imports}==nodes
 evidence=dict(sourceUid=uid,metadata=metadata,podUid=pod['metadata']['uid'],sourceMaps=maps,sourceArchive=str(source_path),sourceSha256=hashlib.sha256(archive).hexdigest(),imageSha256=image_sha,buildLogSha256=hashlib.sha256(logs.encode()).hexdigest(),imports=imports,capturedAt=now())
 evidence_path=directory/(name+'.json');write_json(evidence_path,evidence)
 local=ROOT/f'infra/environments/local/{service}-values.yaml';values=yaml.safe_load(local.read_text(encoding='utf-8'));values['image']['tag']=metadata['tag'];local.write_text(yaml.safe_dump(values,sort_keys=False),encoding='utf-8')
 command=[a.helm,'upgrade','--install',service+'-service',ROOT/'infra/charts/brain-service','-n','brain-apps','--kubeconfig',c.config,'--kube-context','k3d-knowledge-brain','--wait','--timeout','10m']
 for v in [f'infra/services/{service}-values.yaml',f'infra/environments/local/{service}-values.yaml','infra/environments/local/observed-service-values.yaml']:command+=['-f',ROOT/v]
 run(command);after=c.get('deployment',service+'-service','brain-apps');assert before['metadata']['uid']==after['metadata']['uid'] and after['status']['readyReplicas']==1
 assert any(v['name']=='telemetry-agent' for v in after['spec']['template']['spec']['initContainers'])
 for kind in ['service','networkpolicy','pod']:
  obj=c.get(kind,name,'brain-apps')
  if kind=='pod':assert obj['metadata']['uid']==pod['metadata']['uid']
  elif kind=='service':assert obj['spec']['selector']['build-id']==name
  else:assert obj['spec']['podSelector']['matchLabels']['build-id']==name
  assert c.uid()==uid;c.run('delete',kind,name,'-n','brain-apps','--wait=false')
 evidence['cleanupCompletedAt']=now();write_json(evidence_path,evidence)
 latest=json.loads(proof_path.read_text(encoding='utf-8')) if proof_path.exists() else proof;assert latest['sourceUid']==uid;latest['services'][service]=dict(state='COMPLETE',image=metadata['image'],deploymentUid=after['metadata']['uid'],builderEvidence=str(evidence_path),completedAt=now());proof=latest;write_json(proof_path,proof);print('READY='+service+' source and image evidence captured; owned builder cleaned',flush=True)
proof.update(state='DEPLOYED',completedAt=now());write_json(proof_path,proof);print('PASS public discovery deployed;13 services and existing telemetry architecture preserved',flush=True)
