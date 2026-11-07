"""Bounded local outage/restore checks; restore original replicas in finally."""
import json,ssl,subprocess,sys,time,urllib.error,urllib.parse,urllib.request
from contextlib import contextmanager
from local_recovery import Cluster,ROOT,now,write_json

cluster=Cluster(ROOT/'.local/kubeconfig.yaml','knowledge-brain')
TLS=ssl.create_default_context(cafile=str(ROOT/'.local/guest-api-ca.crt'))

@contextmanager
def forward(namespace,service,local,remote):
 process=subprocess.Popen([str(x) for x in cluster.prefix+['-n',namespace,'port-forward','--address=127.0.0.1','service/'+service,str(local)+':'+str(remote)]],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 try:
  yield process
 finally:
  process.terminate()
  try:process.wait(timeout=10)
  except subprocess.TimeoutExpired:process.kill();process.wait()
  process.stdout.close();process.stderr.close()

def get(url):
 with urllib.request.urlopen(url,timeout=5) as response:return json.load(response)

def wait(predicate,timeout=150):
 deadline=time.monotonic()+timeout
 while time.monotonic()<deadline:
  try:
   result=predicate()
   if result:return result
  except (urllib.error.URLError,TimeoutError):pass
  time.sleep(2)
 raise AssertionError('Local fault acceptance deadline exceeded')

cookie=None
def app(path,method='GET',body=None):
 headers={'Content-Type':'application/json','Origin':'https://localhost:8443','Sec-Fetch-Site':'same-origin'}
 if cookie:headers['Cookie']=cookie
 try:
  with urllib.request.urlopen(urllib.request.Request('https://localhost:18443'+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method),context=TLS,timeout=15) as response:return response.status,dict(response.headers),response.read()
 except urllib.error.HTTPError as error:return error.code,dict(error.headers),error.read()

def main():
 global cookie
 installation=json.loads((ROOT/'.local/observability-installation.json').read_text())
 uid=cluster.uid();assert uid==installation['sourceUid']
 integration=cluster.get('deployment','integration-service','brain-apps')
 collector=cluster.get('deployment','brain-otel-collector','brain-observability')
 assert integration['spec']['replicas']==1 and collector['spec']['replicas']==1
 result=dict(sourceUid=uid,startedAt=now())
 with forward('brain-observability','brain-prometheus',19090,9090),forward('brain-observability','brain-alertmanager',19093,9093),forward('brain-apps','gateway-service',18443,8080):
  wait(lambda:get('http://127.0.0.1:19090/api/v1/status/buildinfo'))
  def query(expr):return get('http://127.0.0.1:19090/api/v1/query?'+urllib.parse.urlencode(dict(query=expr)))['data']['result']
  wait(lambda:len(query('brain_application_ready == 1'))==14)
  status,headers,_=app('/api/identity/guest-session','POST',{});assert status==201
  cookie=next(v for k,v in headers.items() if k.lower()=='set-cookie').split(';',1)[0]
  def personal():
   status,_,body=app('/api/spaces/personal');return json.loads(body)['spaceId'] if status==200 else None
  space=wait(personal)
  original=integration['spec']['replicas']
  try:
   cluster.scale(integration,0);print('FAULT=integration-service temporarily unavailable',flush=True)
   wait(lambda:query('brain_application_ready{service_name="integration-service"} == 0'))
   failure=app('/api/spaces/'+space+'/github/repositories')[0];assert failure in [502,503,504]
   firing=wait(lambda:query('ALERTS{alertname="BrainServiceUnavailable",service_name="integration-service",alertstate="firing"}'))
   active=wait(lambda:[a for a in get('http://127.0.0.1:19093/api/v2/alerts') if a['labels'].get('alertname')=='BrainServiceUnavailable' and a['labels'].get('service_name')=='integration-service'])
   result['integrationOutage']=dict(gatewayStatus=failure,prometheusFired=True,alertmanagerReceived=True,firingAt=now())
  finally:
   if cluster.uid()!=uid:raise RuntimeError('Source UID changed; refusing unrelated replica write')
   assert cluster.get('deployment','integration-service','brain-apps')['metadata']['uid']==integration['metadata']['uid']
   cluster.scale(integration,original);cluster.ready('deployment','integration-service','brain-apps')
  wait(lambda:query('brain_application_ready{service_name="integration-service"} == 1'))
  wait(lambda:not query('ALERTS{alertname="BrainServiceUnavailable",service_name="integration-service",alertstate="firing"}'))
  result['integrationOutage'].update(originalReplicasRestored=True,alertResolved=True,resolvedAt=now())
  print('PASS real service outage -> Prometheus firing -> Alertmanager -> recovered and resolved',flush=True)
  try:
   cluster.scale(collector,0);cluster.ready('deployment','brain-otel-collector','brain-observability')
   print('FAULT=telemetry-collector temporarily unavailable',flush=True)
   status,_,body=app('/api/spaces/'+space+'/notes','POST',dict(title='Collector outage continuity fixture',markdown='Domain write must not depend on telemetry delivery'))
   assert status==201;note=json.loads(body);path='/api/spaces/'+space+'/notes/'+note['noteId']
   assert app(path)[0]==200
   time.sleep(5)
   result['collectorOutage']=dict(canonicalWriteStatus=status,canonicalReadStatus=200)
  finally:
   if cluster.uid()!=uid:raise RuntimeError('Source UID changed')
   assert cluster.get('deployment','brain-otel-collector','brain-observability')['metadata']['uid']==collector['metadata']['uid']
   cluster.scale(collector,collector['spec']['replicas']);cluster.ready('deployment','brain-otel-collector','brain-observability')
  def projected():
   status,_,body=app('/api/spaces/'+space+'/activity/timeline?kind=NOTE&timezone=UTC&limit=100')
   return status==200 and any(v['targetPath']==path for v in json.loads(body)['items'])
  wait(projected)
  wait(lambda:len(query('count by (service_name) (jvm_memory_used_bytes)'))==13)
  result['collectorOutage'].update(originalReplicasRestored=True,domainEventProjected=True,all13TelemetryResumed=True)
  print('PASS telemetry outage does not block canonical writes or Outbox/Activity;13 service metrics resumed',flush=True)
  result.update(state='COMPLETE',completedAt=now())
  write_json(ROOT/'.local/observability-fault-validation.json',result)
  print('OBSERVABILITY_FAULTS_VERIFIED='+json.dumps(result),flush=True)

if __name__=='__main__':sys.stdout.reconfigure(encoding='utf-8');main()
