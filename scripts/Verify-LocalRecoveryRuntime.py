"""Read-only restored Registry/Connect checks; credentials remain in mounted Secrets."""
import argparse
import importlib.util
import json
from pathlib import Path
import sys
import time

spec = importlib.util.spec_from_file_location('recovery', Path(__file__).with_name('local_recovery.py'))
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)

VERIFIER = r'''
import hashlib,json,ssl,time,urllib.request,urllib.parse
from pathlib import Path
tls=ssl.create_default_context(cafile='/trust/tls.crt')
issuer='https://brain-keycloak.brain-data.svc:8443/realms/brain-platform'
secret=Path('/clients/reader').read_text().strip()
body=urllib.parse.urlencode(dict(grant_type='client_credentials',client_id='registry-reader',client_secret=secret)).encode()
with urllib.request.urlopen(urllib.request.Request(issuer+'/protocol/openid-connect/token',data=body),context=tls,timeout=20) as response:
 token=json.load(response)['access_token']
def registry(path):
 with urllib.request.urlopen(urllib.request.Request('http://brain-registry.brain-events.svc:8080/apis/registry/v3'+path,headers={'Authorization':'Bearer '+token}),timeout=20) as response:return response.read()
contracts=json.loads(Path('/scripts/manifest.json').read_text())['contracts']
for c in contracts:
 path='/groups/'+c['group']+'/artifacts/'+c['artifact']
 assert hashlib.sha256(registry(path+'/versions/'+c['version']+'/content')).hexdigest()==c['sha256'],'Restored Registry bytes differ'
 for rule,expected in [('VALIDITY','FULL'),('COMPATIBILITY','FULL_TRANSITIVE')]:
  assert json.loads(registry(path+'/rules/'+rule))['config']==expected,'Restored Registry rule differs'
for attempt in range(60):
 try:
  with urllib.request.urlopen('http://brain-connect.brain-events.svc:8083/connectors?expand=status',timeout=10) as response:connectors=json.load(response)
  if len(connectors)==10 and all(c['status']['connector']['state']=='RUNNING' and c['status']['tasks'] and all(t['state']=='RUNNING' for t in c['status']['tasks']) for c in connectors.values()):break
 except Exception:pass
 time.sleep(2)
else:raise AssertionError('All ten restored Outbox connector/tasks did not reach RUNNING')
print('RECOVERY_RUNTIME_VERIFIED='+json.dumps(dict(registryArtifacts=len(contracts),registryBytesAndRulesMatch=True,connectorsRunning=len(connectors),allTasksRunning=True)),flush=True)
'''


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cluster', default=r.SOURCE)
    parser.add_argument('--kubeconfig', default=str(r.ROOT / '.local/kubeconfig.yaml'))
    parser.add_argument('--registry-manifest', required=True)
    args = parser.parse_args()
    if args.cluster != r.SOURCE and not args.cluster.startswith('knowledge-brain-restore-'):
        raise ValueError('An explicit local source/restore context is required')
    cluster = r.Cluster(args.kubeconfig, args.cluster)
    manifest = json.loads(Path(args.registry_manifest).read_text(encoding='utf-8'))
    if not manifest['contracts']:
        raise ValueError('Expected contract manifest is empty')
    name = 'recovery-runtime-' + str(int(time.time()))
    cluster.apply([dict(apiVersion='v1', kind='ConfigMap', metadata=dict(name=name, namespace='brain-data'), data={'verify.py': VERIFIER, 'manifest.json': json.dumps(manifest)})])
    job = dict(apiVersion='batch/v1', kind='Job', metadata=dict(name=name, namespace='brain-data'), spec=dict(backoffLimit=0, activeDeadlineSeconds=240, ttlSecondsAfterFinished=86400,
        template=dict(metadata=dict(labels={'brain-connect-admin': 'true'}), spec=dict(restartPolicy='Never', automountServiceAccountToken=False,
        securityContext=dict(runAsNonRoot=True, runAsUser=10001, runAsGroup=10001),
        containers=[dict(name='verify', image='python:3.13.7-alpine@sha256:9ba6d8cbebf0fb6546ae71f2a1c14f6ffd2fdab83af7fa5669734ef30ad48844', command=['python','-u','/scripts/verify.py'],
        securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),
        resources=dict(requests=dict(cpu='50m',memory='64Mi'),limits=dict(cpu='250m',memory='128Mi')),
        volumeMounts=[dict(name=n,mountPath='/'+n,readOnly=True) for n in ['scripts','clients','trust']])],
        volumes=[dict(name='scripts',configMap=dict(name=name)),dict(name='clients',secret=dict(secretName='brain-registry-clients',items=[dict(key='reader',path='reader')])),
                 dict(name='trust',secret=dict(secretName='brain-keycloak-tls',items=[dict(key='tls.crt',path='tls.crt')]))]))))
    cluster.apply([job])
    uid = cluster.get('job', name, 'brain-data')['metadata']['uid']
    cluster.run('patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[dict(apiVersion='batch/v1',kind='Job',name=name,uid=uid)]}}))
    print('JOB='+name,flush=True)
    deadline = time.monotonic()+270
    while time.monotonic()<deadline:
        status = cluster.get('job',name,'brain-data').get('status',{})
        if status.get('failed'):
            print(cluster.run('logs','job/'+name,'-n','brain-data').decode(),flush=True)
            raise RuntimeError('Restored runtime validation failed')
        if status.get('succeeded'):
            logs=cluster.run('logs','job/'+name,'-n','brain-data').decode()
            print(logs,flush=True)
            result=json.loads(logs.split('RECOVERY_RUNTIME_VERIFIED=')[1].splitlines()[0])
            result.update(job=name,uid=uid,clusterUid=cluster.uid(),verifiedAt=r.now())
            r.write_json(r.ROOT/'.local/recovery-runs'/(args.cluster+'-runtime.json'),result)
            return
        time.sleep(3)
    raise TimeoutError('Runtime verifier deadline exceeded')


if __name__=='__main__':
    main()
