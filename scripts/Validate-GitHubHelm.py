"""Read-only Helm contract checks for GitHub authority and recovery wiring."""
from pathlib import Path
import argparse,subprocess,shutil,yaml
r=Path(__file__).resolve().parents[1]
args=argparse.ArgumentParser();args.add_argument('--helm',default=shutil.which('helm'));opt=args.parse_args();assert opt.helm,'--helm required'
expected={'space':['BRAIN_SPACE_INTEGRATION_AUTHORIZATION_ENABLED','BRAIN_SPACE_INTEGRATION_AUTHORIZATION_KEY'],'integration':['BRAIN_INTEGRATION_API_ENABLED','BRAIN_INTEGRATION_PERSISTENCE_ENABLED','BRAIN_INTEGRATION_ACTIVITY_EXPORT_ENABLED','BRAIN_INTEGRATION_SPACE_AUTHORIZATION_KEY'],'activity':['BRAIN_ACTIVITY_INTEGRATION_ORIGIN','BRAIN_ACTIVITY_INTEGRATION_EXPORT_KEY']}
for service,names in expected.items():
 for local in [False,True]:
  cmd=[opt.helm,'template',service+'-service',str(r/'infra/charts/brain-service'),'-n','brain-apps','-f',str(r/f'infra/services/{service}-values.yaml'),'--set','image.tag=contract-check','--set','image.repository=knowledge-brain/'+service+'-service']
  if local:cmd+=['-f',str(r/f'infra/environments/local/{service}-values.yaml')]
  docs=list(yaml.safe_load_all(subprocess.check_output(cmd,text=True,encoding='utf-8')));spec=next(d for d in docs if d and d['kind']=='Deployment')['spec']['template']['spec'];env={v['name']:v for v in spec['containers'][0]['env']}
  for name in names:assert name in env,(service,local,name)
  if service=='integration':
   for name in ['BRAIN_INTEGRATION_API_ENABLED','BRAIN_INTEGRATION_PERSISTENCE_ENABLED','BRAIN_INTEGRATION_ACTIVITY_EXPORT_ENABLED']:assert env[name]['value']=='true'
   tls=next(v for v in spec['volumes'] if v['name']=='api-tls');assert tls['secret']['secretName']=='brain-integration-api-tls'
   assert env['BRAIN_INTEGRATION_SPACE_AUTHORIZATION_KEY']['valueFrom']['secretKeyRef']['name']=='brain-integration-space-authorization'
   assert 'integrationDatabase' in env['MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE']['value']
  print('PASS GitHub Helm wiring:',service,'local' if local else 'production')
