"""In-cluster acceptance checks; logs contain only bounded outcomes, never credentials."""
import base64,concurrent.futures,hashlib,http.cookiejar,json,os,secrets,ssl,statistics,time,urllib.error,urllib.parse,urllib.request
from pathlib import Path

BASE='https://gateway-service.brain-apps.svc:8080'
TLS=ssl.create_default_context(cafile='/tls/ca.crt')
MUTUAL=ssl.create_default_context(cafile='/tls/ca.crt');MUTUAL.load_cert_chain('/tls/tls.crt','/tls/tls.key')
REPORT={};MODE=os.getenv('BRAIN_OBSERVABILITY_TEST','canary')

def request(url,method='GET',body=None,headers=None,context=TLS):
 data=json.dumps(body).encode() if body is not None else None
 h={'Accept':'application/json'};h.update(headers or {})
 if data is not None:h['Content-Type']='application/json'
 try:
  with urllib.request.urlopen(urllib.request.Request(url,data=data,method=method,headers=h),context=context,timeout=12) as response:return response.status,dict(response.headers),response.read()
 except urllib.error.HTTPError as error:return error.code,dict(error.headers),error.read()

def doc(url,headers=None):
 status,_,body=request(url,headers=headers);assert status==200,'HTTP read failed '+str(status);return json.loads(body)

def wait(predicate,timeout=120):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  try:
   value=predicate()
   if value:return value
  except (urllib.error.URLError,TimeoutError,AssertionError):pass
  time.sleep(3)
 raise AssertionError('Acceptance condition deadline exceeded')

def prometheus(expr):return doc('http://brain-prometheus:9090/api/v1/query?'+urllib.parse.urlencode(dict(query=expr)))['data']['result']
def logs(query,limit=100):return doc('http://brain-loki:3100/loki/api/v1/query_range?'+urllib.parse.urlencode(dict(query=query,limit=limit,since='1h')))['data']['result']
def trace(identifier):
 status,_,body=request('http://brain-tempo:3200/api/traces/'+identifier)
 return json.loads(body) if status==200 else None

def canary():
 wait(lambda:prometheus('jvm_memory_used_bytes{service_name="integration-service"}'))
 wait(lambda:logs('{service_name="integration-service"}'))
 secret='PRIVATE-OBS-CANARY-'+secrets.token_hex(16);identifier=secrets.token_hex(16);span=secrets.token_hex(8);now=time.time_ns()
 attrs=lambda k,v:dict(key=k,value=dict(stringValue=v))
 payload=dict(resourceSpans=[dict(resource=dict(attributes=[attrs('service.name','integration-service'),attrs('private.record',secret)]),scopeSpans=[dict(scope=dict(name=secret),spans=[dict(traceId=identifier,spanId=span,name=secret,kind=2,startTimeUnixNano=str(now),endTimeUnixNano=str(now+1000000),attributes=[attrs('url.full','https://example.invalid/?token='+secret),attrs('db.statement',secret),attrs('private.body',secret)],events=[dict(timeUnixNano=str(now),name=secret,attributes=[attrs('private.body',secret)])],status=dict(code=2,message=secret)),dict(traceId=identifier,spanId=secrets.token_hex(8),name=secret,startTimeUnixNano=str(now),endTimeUnixNano=str(now+1000000),links=[dict(traceId=secrets.token_hex(16),spanId=secrets.token_hex(8),attributes=[attrs('private.body',secret)])])])])])
 status,_,_=request('https://brain-otel-collector:4318/v1/traces','POST',payload,context=MUTUAL);assert status==200,'Synthetic trace rejected'
 payload=dict(resourceLogs=[dict(resource=dict(attributes=[attrs('service.name','integration-service'),attrs('private.record',secret)]),scopeLogs=[dict(scope=dict(name=secret),logRecords=[dict(timeUnixNano=str(now),observedTimeUnixNano=str(now),traceId=identifier,spanId=span,severityNumber=9,severityText='INFO',body=dict(stringValue='gateway_response trace_id='+identifier+' '+secret),attributes=[attrs('cookie',secret)])])])])
 assert request('https://brain-otel-collector:4318/v1/logs','POST',payload,context=MUTUAL)[0]==200
 stored=wait(lambda:trace(identifier));assert secret not in json.dumps(stored),'Private trace data reached storage'
 found=wait(lambda:logs('{service_name="integration-service"} | trace_id="'+identifier+'"'))
 assert secret not in json.dumps(found),'Private log data reached storage'
 assert all(value[1]=='operational log' for row in found for value in row['values'])
 try:
  result=request('https://brain-otel-collector:4318/v1/traces','POST',{},context=TLS)
  assert result[0] not in [200,201,202],'Unauthenticated ingest accepted'
 except (urllib.error.URLError,ssl.SSLError,ConnectionError):pass
 REPORT.update(canaryMetrics=True,canaryLogs=True,traceAndLogPrivacy=True,mutualTlsRequired=True,syntheticTraceId=identifier)

class Browser:
 def __init__(self):self.cookie=None;self.space=None
 def call(self,path,method='GET',body=None,extra=None):
  headers={'Origin':'https://localhost:8443','Sec-Fetch-Site':'same-origin'}
  if self.cookie:headers['Cookie']=self.cookie
  headers.update(extra or {})
  return request(BASE+path,method,body,headers)
 def start(self):
  status,headers,_=self.call('/api/identity/guest-session','POST',{});assert status==201
  self.cookie=next(v for k,v in headers.items() if k.lower()=='set-cookie').split(';',1)[0]
  def personal():
   status,_,body=self.call('/api/spaces/personal')
   return json.loads(body)['spaceId'] if status==200 else None
  self.space=wait(personal);return self

def acceptance():
 expected={s+'-service' for s in ['gateway','identity','space','knowledge','content','learning','planning','collaboration','activity','media','integration','notification','brain-query']}
 samples=wait(lambda:prometheus('count by (service_name) (jvm_memory_used_bytes)') if len(prometheus('count by (service_name) (jvm_memory_used_bytes)'))>=13 else None)
 assert expected.issubset({s['metric']['service_name'] for s in samples})
 for service in sorted(expected):wait(lambda service=service:logs('{service_name="'+service+'"}',1))
 assert float(prometheus('sum(brain_cdc_connector_running)')[0]['value'][1])==10
 assert prometheus('kafka_consumergroup_lag'),'Kafka lag not exported'
 user=Browser().start();foreign=Browser().start();identifier=secrets.token_hex(16);secret='PRIVATE-NOTE-'+secrets.token_hex(16)
 status,headers,raw=user.call('/api/spaces/'+user.space+'/notes','POST',dict(title='Observability private fixture',markdown=secret),{'traceparent':'00-'+identifier+'-'+secrets.token_hex(8)+'-01'})
 assert status==201
 assert next(v for k,v in headers.items() if k.lower()=='x-trace-id')==identifier
 note=json.loads(raw);path='/api/spaces/'+user.space+'/notes/'+note['noteId']
 assert foreign.call(path)[0]==404
 stored=wait(lambda:trace(identifier));assert secret not in json.dumps(stored)
 services=set()
 def walk(value):
  if isinstance(value,dict):
   if value.get('key')=='service.name':services.add(value['value']['stringValue'])
   for v in value.values():walk(v)
  elif isinstance(value,list):
   for v in value:walk(v)
 walk(stored);assert {'gateway-service','content-service'}.issubset(services),'HTTP trace is disconnected'
 event_logs=wait(lambda:logs('{service_name="activity-service"} | domain_trace_id="'+identifier+'"'),150)
 assert secret not in json.dumps(event_logs)
 # No incoming header: a sampled real browser root must match X-Trace-Id too.
 identifiers=[];durations=[];statuses=[]
 def read(_):
  start=time.perf_counter();status,headers,_=user.call(path);elapsed=time.perf_counter()-start
  return status,elapsed,next(v for k,v in headers.items() if k.lower()=='x-trace-id')
 for attempt in range(3):
  with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:results=list(pool.map(read,range(40)))
  statuses += [v[0] for v in results];durations += [v[1] for v in results];identifiers += [v[2] for v in results]
  time.sleep(5)
  if any(trace(value) for value in identifiers):break
 else:raise AssertionError('No-header browser trace did not match returned correlation ID')
 assert all(s==200 for s in statuses)
 password=Path('/admin/password').read_text().strip();basic='Basic '+base64.b64encode(('brain-admin:'+password).encode()).decode()
 assert request('https://brain-grafana:3000/api/user')[0]==401
 assert doc('https://brain-grafana:3000/api/user',{'Authorization':basic})['login']=='brain-admin'
 dashboard=doc('https://brain-grafana:3000/api/dashboards/uid/brain-platform',{'Authorization':basic})['dashboard']
 assert len(dashboard['panels'])==18
 REPORT.update(all13Metrics=True,all13Logs=True,kafkaLag=True,connectorsRunning=10,httpTraceServices=sorted(services),eventLogCorrelation=True,noHeaderTraceMatches=True,privateNoteNotInTelemetry=True,foreignSpaceDenied=True,grafanaAnonymousDenied=True,grafanaDashboardPanels=18,boundedLoad=dict(requests=len(durations),concurrency=4,errors=0,p50Seconds=statistics.median(durations),p95Seconds=sorted(durations)[int(len(durations)*0.95)-1]),realTraceId=identifier)

canary()
if MODE=='acceptance':acceptance()
print('OBSERVABILITY_VERIFIED='+json.dumps(REPORT),flush=True)
