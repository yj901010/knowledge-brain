"""Browser and least-privilege checks in the restricted operator namespace."""
import base64,json,subprocess,sys,time
from local_recovery import Cluster,ROOT,now,write_json

cluster=Cluster(ROOT/'.local/kubeconfig.yaml','knowledge-brain')

def run_job(name,image,command,data,labels,volumes,mounts,memory='128Mi'):
 cluster.apply([dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-observability'),data=data)])
 volumes=[dict(name='source',configMap=dict(name=name)),*volumes];mounts=[dict(name='source',mountPath='/source',readOnly=True),*mounts]
 cluster.apply([dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-observability'),spec=dict(backoffLimit=0,activeDeadlineSeconds=600,ttlSecondsAfterFinished=86400,template=dict(metadata=dict(labels=labels),spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,runAsGroup=10001,fsGroup=10001,seccompProfile=dict(type='RuntimeDefault')),containers=[dict(name='verify',image=image,command=command,securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='100m',memory='128Mi'),limits=dict(cpu='1500m',memory=memory)),volumeMounts=mounts)],volumes=volumes))))])
 uid=cluster.get('job',name,'brain-observability')['metadata']['uid'];cluster.run('patch','configmap',name,'-n','brain-observability','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[dict(apiVersion='batch/v1',kind='Job',name=name,uid=uid)]}}))
 print('JOB='+name,flush=True);deadline=time.monotonic()+630
 while time.monotonic()<deadline:
  status=cluster.get('job',name,'brain-observability').get('status',{})
  if status.get('succeeded') or status.get('failed'):
   logs=cluster.run('logs','job/'+name,'-n','brain-observability').decode()
   for line in logs.splitlines():
    if line.startswith('MONITORING_UI_PNG='):(ROOT/'.local/observability-ui.png').write_bytes(base64.b64decode(line.split('=',1)[1]))
    else:print(line,flush=True)
   if status.get('failed'):raise RuntimeError('Monitoring boundary validation failed')
   return dict(job=name,uid=uid)
  time.sleep(3)
 raise TimeoutError('Boundary validation deadline exceeded')

def main():
 public=[dict(name='trust',secret=dict(secretName='brain-telemetry-client',items=[dict(key='ca.crt',path='ca.crt')]))]
 mount=[dict(name='trust',mountPath='/trust',readOnly=True)]
 code='''import errno,json,socket,ssl,urllib.request,urllib.error
tls=ssl.create_default_context(cafile='/trust/ca.crt')
try:
 urllib.request.urlopen('https://brain-grafana:3000/api/health',context=tls,timeout=4)
 raise AssertionError('Unlabelled Pod reached private Grafana')
except urllib.error.URLError as error:
 assert isinstance(error.reason,TimeoutError) or isinstance(error.reason,OSError) and error.reason.errno in (errno.EHOSTUNREACH,errno.ECONNREFUSED),type(error.reason)
except TimeoutError:pass
print('PASS unlabelled Pod cannot reach private monitoring backend')
'''
 negative=run_job('monitoring-network-'+str(int(time.time())),'python:3.13.7-alpine',['python','/source/negative.py'],{'negative.py':code},{'brain-negative-network-check':'true'},public,mount)
 decisions={}
 for verb,resource,ns,expected in [('get','pods','brain-apps',True),('get','secrets','brain-data',False),('patch','deployments','brain-apps',False),('get','pods','kube-system',False)]:
  command=[*cluster.prefix,'auth','can-i',verb,resource,'-n',ns,'--as=system:serviceaccount:brain-observability:brain-observability-observer']
  result=subprocess.run([str(a) for a in command],capture_output=True,text=True)
  allowed=result.stdout.strip()=='yes';assert allowed==expected
  decisions[verb+':'+resource+':'+ns]=allowed
 print('PASS observer reads scoped metadata and cannot read Secrets, write deployments or read kube-system',flush=True)
 data={n:(ROOT/'apps/graph-web'/n).read_text(encoding='utf-8') for n in ['package.json','package-lock.json']}
 data['monitoring-ui.spec.ts']=(ROOT/'scripts/monitoring-ui.spec.ts').read_text(encoding='utf-8')
 data['playwright.config.ts']='import {defineConfig} from "@playwright/test"; export default defineConfig({testDir:"./tests",timeout:180000,workers:1,use:{headless:true,viewport:{width:1440,height:1000}},reporter:"list"});'
 data['bridge.js']="const net=require('net');net.createServer(c=>{const s=net.connect(3000,'brain-grafana');c.pipe(s);s.pipe(c);c.on('error',()=>s.destroy());s.on('error',()=>c.destroy())}).listen(8446,'127.0.0.1');"
 data['run.sh']='''set -euo pipefail
export HOME=/tmp/monitor-home NODE_EXTRA_CA_CERTS=/trust/ca.crt
mkdir -p /tmp/apt/lists/partial /tmp/apt/cache/archives/partial /tmp/nss /tmp/build/tests "$HOME/.pki/nssdb"
cd /tmp/apt
apt-get -o Dir::State::lists=/tmp/apt/lists -o Dir::Cache=/tmp/apt/cache -o Debug::NoLocking=1 -o APT::Sandbox::User=root update -qq
apt-get -o Dir::State::lists=/tmp/apt/lists -o Dir::Cache=/tmp/apt/cache -o Debug::NoLocking=1 -o APT::Sandbox::User=root download libnss3-tools >/dev/null
for package in ./*.deb; do dpkg-deb -x "$package" /tmp/nss; done
export PATH=/tmp/nss/usr/bin:$PATH
certutil -N -d sql:$HOME/.pki/nssdb --empty-password
certutil -A -d sql:$HOME/.pki/nssdb -n brain-local -t 'C,,' -i /trust/ca.crt
cp /source/package*.json /source/playwright.config.ts /tmp/build/
cp /source/monitoring-ui.spec.ts /tmp/build/tests/
node /source/bridge.js &
cd /tmp/build
npm ci --no-audit --no-fund >/dev/null
npx playwright test --workers=1
'''
 volumes=[*public,dict(name='admin',secret=dict(secretName='brain-grafana-admin')),dict(name='temporary',emptyDir=dict(sizeLimit='1Gi')),dict(name='shm',emptyDir=dict(medium='Memory',sizeLimit='256Mi'))]
 mounts=[*mount,dict(name='admin',mountPath='/admin',readOnly=True),dict(name='temporary',mountPath='/tmp'),dict(name='shm',mountPath='/dev/shm')]
 ui=run_job('monitoring-ui-'+str(int(time.time())),'mcr.microsoft.com/playwright:v1.64.0-noble',['bash','/source/run.sh'],data,{'brain-observability-validation':'true'},volumes,mounts,'1536Mi')
 write_json(ROOT/'.local/observability-boundary-validation.json',dict(sourceUid=cluster.uid(),network=negative,observerRbac=decisions,operatorBrowser=ui,verifiedAt=now()))

if __name__=='__main__':sys.stdout.reconfigure(encoding='utf-8');main()
