"""Reproduce duplicate CA-alias initialization and verify same-emptyDir retries without secrets."""
import argparse,json,shutil,subprocess,time
from pathlib import Path
import yaml
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--helm',required=True);a=p.parse_args()
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 result=subprocess.run(k+args,input=body,text=True,encoding='utf-8',capture_output=True)
 if result.returncode:raise RuntimeError('Cassandra truststore validation command failed')
 return result.stdout
assert run(['config','current-context']).strip()=='k3d-knowledge-brain'
render=subprocess.run([a.helm,'template','truststore-validation',str(root/'infra/charts/brain-cassandra'),'--set','enabled=true'],text=True,encoding='utf-8',capture_output=True)
if render.returncode:raise RuntimeError('Cassandra template render failed')
stateful=next(doc for doc in yaml.safe_load_all(render.stdout) if doc and doc['kind']=='StatefulSet')
pod=stateful['spec']['template']['spec'];init=pod['initContainers'][0]
command=init['args'][0];prefix='rm -f -- /trust/truststore.p12; '
assert command.startswith(prefix),'Production retry-safe command required'
original=command[len(prefix):]
check='keytool -list -alias brain-ca -keystore /trust/truststore.p12 -storetype PKCS12 -storepass:env TRUST_PASSWORD >/dev/null 2>&1'
shell=original+'; '+check+'; if '+original+'; then echo UNEXPECTED_DUPLICATE_ALIAS_SUCCESS; exit 1; else echo ORIGINAL_REPEAT_IMPORT_REJECTED; fi; '+command+'; '+check+'; '+command+'; '+check+'; echo CASSANDRA_TRUSTSTORE_RETRY_VERIFIED'
name='cassandra-truststore-validation-'+str(int(time.time()))
container={'name':'verify','image':init['image'],'command':['bash','-ec',shell],'env':init['env'],'securityContext':init['securityContext'],'resources':init['resources'],'volumeMounts':init['volumeMounts']}
volumes=[volume for volume in pod['volumes'] if volume['name'] in ('server-tls','trust','tmp')]
job={'apiVersion':'batch/v1','kind':'Job','metadata':{'name':name,'namespace':'brain-data'},'spec':{'backoffLimit':0,'activeDeadlineSeconds':120,'ttlSecondsAfterFinished':86400,'template':{'metadata':{'labels':{'cassandra-truststore-validation':name}},'spec':{'restartPolicy':'Never','automountServiceAccountToken':False,'securityContext':pod['securityContext'],'containers':[container],'volumes':volumes}}}}
run(['apply','-f','-'],json.dumps(job));print('JOB='+name,flush=True)
try:
 for _ in range(120):
  state=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))
  if state.get('status',{}).get('succeeded') or state.get('status',{}).get('failed'):break
  time.sleep(1)
 logs=run(['logs','job/'+name,'-n','brain-data'])
 assert state.get('status',{}).get('succeeded') and 'CASSANDRA_TRUSTSTORE_RETRY_VERIFIED' in logs
 evidence='JOB='+name+'\n'+logs+'PASS original repeated alias rejected; production initialization twice succeeds on the same emptyDir\n'
 (root/'.local/cassandra-truststore-validation.txt').write_text(evidence,encoding='utf-8');print(evidence,flush=True)
finally:
 run(['delete','job/'+name,'-n','brain-data','--wait=false','--ignore-not-found'])
