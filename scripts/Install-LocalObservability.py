"""Install private infrastructure and roll opted-in services one at a time."""
import argparse,json,os,shutil,subprocess,sys,time
from pathlib import Path
from local_recovery import Cluster,ROOT,write_json,now

SERVICES=['integration','identity','space','content','knowledge','learning','planning','media','collaboration','notification','brain-query','activity','gateway']

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--helm',default=shutil.which('helm') or 'helm')
 parser.add_argument('--service',choices=SERVICES)
 parser.add_argument('--infrastructure-only',action='store_true')
 args=parser.parse_args();cluster=Cluster(ROOT/'.local/kubeconfig.yaml','knowledge-brain')
 env=os.environ.copy()
 for kind in ['CACHE','CONFIG','DATA']:env['HELM_'+kind+'_HOME']=str(ROOT/f'.local/helm/{kind.lower()}')
 def run(command):
  result=subprocess.run([str(x) for x in command],capture_output=True,text=True,encoding='utf-8',env=env,timeout=700)
  if result.returncode:raise RuntimeError(result.stderr[:1800])
  return result.stdout
 def helm(release,chart,namespace,*extra):
  return run([args.helm,'upgrade','--install',release,ROOT/'infra/charts'/chart,'-n',namespace,'--kubeconfig',ROOT/'.local/kubeconfig.yaml','--kube-context','k3d-knowledge-brain',*extra,'--wait','--timeout','10m'])
 state_path=ROOT/'.local/observability-installation.json'
 state=json.loads(state_path.read_text()) if state_path.exists() else dict(sourceUid=cluster.uid(),services={},startedAt=now())
 if state['sourceUid']!=cluster.uid():raise ValueError('Recorded source cluster UID changed')
 if args.infrastructure_only:
  run([sys.executable,ROOT/'scripts/Provision-Observability.py'])
  print(helm('brain-observability','brain-observability','brain-observability','-f',ROOT/'infra/environments/local/observability-values.yaml'),flush=True)
  print(helm('brain-kafka','brain-kafka','brain-events','-f',ROOT/'infra/environments/local/kafka-values.yaml'),flush=True)
  state['infrastructureReadyAt']=now();write_json(state_path,state);return
 for service in [args.service] if args.service else SERVICES:
  name=service+'-service'
  before=cluster.get('deployment',name,'brain-apps')
  print('INSTRUMENTING='+name,flush=True)
  print(helm(name,'brain-service','brain-apps','-f',ROOT/f'infra/services/{service}-values.yaml','-f',ROOT/f'infra/environments/local/{service}-values.yaml','-f',ROOT/'infra/environments/local/observed-service-values.yaml'),flush=True)
  after=cluster.get('deployment',name,'brain-apps')
  state['services'][service]=dict(beforeUid=before['metadata']['uid'],afterUid=after['metadata']['uid'],image=after['spec']['template']['spec']['containers'][0]['image'],readyAt=now())
  write_json(state_path,state)
  print('READY='+name,flush=True)
 state['updatedAt']=now();write_json(state_path,state)

if __name__=='__main__':
 sys.stdout.reconfigure(encoding='utf-8');main()
