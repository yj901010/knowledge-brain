"""Bounded closed-loop local API workload; only fresh private test principals write.

No credentials, bodies, query text or domain IDs are written to logs/reports.
Standard library only. A diagnostic, not a production SLA/capacity certificate.
"""
import concurrent.futures,http.client,json,os,random,secrets,ssl,statistics,threading,time,urllib.parse
from collections import Counter,defaultdict

TLS=ssl.create_default_context(cafile='/trust/ca.crt');DURATION=int(os.environ['BRAIN_LOAD_SECONDS'])
assert 60<=DURATION<=900
HOST='gateway-service.brain-apps.svc';PREFIX='Operations-'+secrets.token_hex(12)
STOP=threading.Event();LOCK=threading.Lock();LATENCIES=defaultdict(list);STATUSES=Counter();FAILURES=[];WRITES=[];SAMPLES=[]

class Client:
 def __init__(self,cookie=None):self.cookie=cookie;self.connection=None
 def close(self):
  if self.connection:self.connection.close();self.connection=None
 def call(self,path,method='GET',body=None,etag=None):
  if self.connection is None:self.connection=http.client.HTTPSConnection(HOST,8080,context=TLS,timeout=10)
  headers={'Origin':'https://localhost:8443','Sec-Fetch-Site':'same-origin','Accept':'application/json'}
  if self.cookie:headers['Cookie']=self.cookie
  if etag:headers['If-Match']=etag
  if body is not None:headers['Content-Type']='application/json'
  try:
   self.connection.request(method,path,json.dumps(body).encode() if body is not None else None,headers)
   response=self.connection.getresponse();raw=response.read(2*1024*1024+1);assert len(raw)<=2*1024*1024,'Response bound'
   status=response.status;h=dict(response.getheaders());data=json.loads(raw) if raw else None
   if response.will_close:self.close()
   return status,h,data
  except BaseException:self.close();raise

def wait(fn,seconds=75):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  result=fn()
  if result:return result
  time.sleep(.5)
 raise AssertionError('Projection or identity readiness deadline')

def actor(index):
 c=Client();status,headers,_=c.call('/api/identity/guest-session','POST',{});assert status==201
 c.cookie=next(v for k,v in headers.items() if k.lower()=='set-cookie').split(';',1)[0]
 def personal():
  status,_,body=c.call('/api/spaces/personal');return body['spaceId'] if status==200 else None
 space=wait(personal);notes=[]
 for i in range(4):
  title=f'{PREFIX}-{index}-{i}';markdown=PREFIX if i==0 else PREFIX+'\n[['+f'{PREFIX}-{index}-0'+']]'
  status,_,note=c.call('/api/spaces/'+space+'/notes','POST',dict(title=title,markdown=markdown));assert status==201;notes.append(note)
 a=dict(cookie=c.cookie,space=space,notes=notes,ids={n['noteId'] for n in notes});c.close();return a

def graph_ready(a):
 c=Client(a['cookie'])
 try:
  def ready():
   status,_,body=c.call('/api/spaces/'+a['space']+'/graph/notes?limit=200')
   return status==200 and {n['noteId'] for n in body['nodes']}==a['ids'] and len(body['links'])==3
  wait(ready)
 finally:c.close()

def metric(expr):
 c=http.client.HTTPConnection('brain-prometheus',9090,timeout=5)
 try:
  c.request('GET','/api/v1/query?'+urllib.parse.urlencode(dict(query=expr)));r=c.getresponse();assert r.status==200
  body=json.loads(r.read());assert body['status']=='success';return body['data']['result']
 finally:c.close()

def monitoring():
 values={name:float(metric(expr)[0]['value'][1]) for name,expr in {
  'jvmServices':'count(count by(service_name)(jvm_memory_used_bytes))','connectors':'sum(brain_cdc_connector_running)',
  'tasks':'sum(brain_cdc_tasks_running)','jvmUsedBytes':'sum(jvm_memory_used_bytes)',
  'readyApps':'sum(brain_application_ready)'}.items()}
 values['elapsedSeconds']=round(time.monotonic()-BEGAN,2);SAMPLES.append(values)
 print('LOAD_HEARTBEAT='+json.dumps(values),flush=True)
 assert values['jvmServices']==13 and values['connectors']==values['tasks']==10 and values['readyApps']==14,'Platform readiness changed'

def fail(kind,error):
 with LOCK:
  if len(FAILURES)<20:FAILURES.append(dict(kind=kind,errorType=type(error).__name__))
 STOP.set()

def reader(index,phase,end):
 rng=random.Random(index+phase*100);a=ACTORS[index%2];other=ACTORS[1-index%2];c=Client(a['cookie']);turn=0
 try:
  while time.monotonic()<end and not STOP.is_set():
   began=time.monotonic();choice=turn%10;turn+=1
   if choice<4:kind='canonical-note';path='/api/spaces/'+a['space']+'/notes/'+a['notes'][choice%4]['noteId'];expected=200
   elif choice<6:kind='private-graph';path='/api/spaces/'+a['space']+'/graph/notes?limit=200';expected=200
   elif choice<8:kind='public-search';path='/api/public/spaces/'+PUBLIC+'/search?q=Discovery&kind=CONCEPT&limit=20';expected=200
   elif choice==8:kind='public-graph';path='/api/public/spaces/'+PUBLIC+'/graph?limit=40&depth=2';expected=200
   else:kind='foreign-note-denied';path='/api/spaces/'+other['space']+'/notes/'+other['notes'][0]['noteId'];expected=404
   status,_,body=c.call(path);elapsed=time.monotonic()-began
   with LOCK:LATENCIES[f'{phase}:{kind}'].append(elapsed);STATUSES[f'{phase}:{kind}:{status}']+=1
   assert status==expected,'Unexpected bounded API status'
   if kind=='canonical-note':assert body['noteId'] in a['ids'] and PREFIX in body['markdown']
   if kind=='private-graph':assert {n['noteId'] for n in body['nodes']}==a['ids'] and len(body['links'])==3
   if kind.startswith('public-') or kind=='foreign-note-denied':assert PREFIX not in json.dumps(body),'Private canary escaped'
   if kind=='public-search':assert len(body['items'])<=20 and all(v['version']>=1 for v in body['items'])
   STOP.wait(max(0,1-(time.monotonic()-began)))
 except BaseException as e:fail(kind,e)
 finally:c.close()

def writer():
 clients=[Client(a['cookie']) for a in ACTORS];turn=0
 try:
  while not STOP.is_set():
   a=ACTORS[turn%2];c=clients[turn%2];note=a['notes'][0];path='/api/spaces/'+a['space']+'/notes/'+note['noteId'];turn+=1
   status,headers,old=c.call(path);assert status==200
   etag=next(v for k,v in headers.items() if k.lower()=='etag');began=time.monotonic()
   status,_,new=c.call(path,'PUT',dict(title=old['title'],markdown=PREFIX+' revision '+str(old['version']+1)),etag)
   assert status==200 and new['version']==old['version']+1
   a['notes'][0]=new
   with LOCK:WRITES.append(time.monotonic()-began)
   if STOP.wait(10):break
 except BaseException as e:fail('canonical-write',e)
 finally:
  for c in clients:c.close()

ACTORS=[actor(i) for i in range(2)]
for a in ACTORS:graph_ready(a)
c=Client(ACTORS[0]['cookie']);status,_,spaces=c.call('/api/spaces/public');assert status==200
PUBLIC=next(s['spaceId'] for s in spaces['items'] if s['domain']=='DEVELOPMENT');c.close()
BEGAN=time.monotonic();monitoring();w=threading.Thread(target=writer);w.start()
try:
 durations=[DURATION//5,DURATION*3//5,DURATION-DURATION//5-DURATION*3//5]
 for phase,(seconds,workers) in enumerate(zip(durations,[2,4,8]),1):
  end=time.monotonic()+seconds;print('LOAD_PHASE='+json.dumps(dict(phase=phase,seconds=seconds,workers=workers,perWorkerPeriodSeconds=1)),flush=True)
  with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
   tasks=[pool.submit(reader,i,phase,end) for i in range(workers)]
   while any(not f.done() for f in tasks):
    if STOP.wait(min(30,max(.1,end-time.monotonic()))):break
    monitoring()
   for future in tasks:future.result()
  if STOP.is_set():break
finally:STOP.set();w.join(timeout=15)
assert not w.is_alive()

tail=[]
for a in ACTORS:
 c=Client(a['cookie']);path='/api/spaces/'+a['space']+'/activity/timeline?kind=NOTE&timezone=UTC&limit=100';note=a['notes'][0]
 def projected():
  status,_,body=c.call(path)
  if status!=200:return None
  rows=[v for v in body['items'] if v['aggregateId']==note['noteId']]
  return rows if len(rows)==note['version'] and {v['sourceVersion'] for v in rows}==set(range(1,note['version']+1)) else None
 rows=wait(projected);assert len({v['eventId'] for v in rows})==len(rows);tail.append(dict(versions=len(rows),eventsUnique=True))
 status,_,history=c.call('/api/spaces/'+a['space']+'/notes/'+note['noteId']+'/revisions?limit=5');assert status==200
 c.close()
c=Client(ACTORS[0]['cookie']);assert c.call('/api/identity/guest-session/revoke','POST',{})[0]==204
assert c.call('/api/spaces/'+ACTORS[0]['space']+'/notes/'+ACTORS[0]['notes'][0]['noteId'])[0]==401;c.close()
c=Client(ACTORS[1]['cookie']);assert c.call('/api/spaces/'+ACTORS[1]['space']+'/notes/'+ACTORS[1]['notes'][0]['noteId'])[0]==200;c.close();monitoring()
def summary(values):
 values=sorted(values)
 return dict(count=len(values),p50Ms=round(statistics.median(values)*1000,2),p95Ms=round(values[max(0,int(len(values)*.95)-1)]*1000,2),p99Ms=round(values[max(0,int(len(values)*.99)-1)]*1000,2),maxMs=round(values[-1]*1000,2))
result=dict(state='PASSED' if not FAILURES else 'FAILED',durationSeconds=DURATION,actualSeconds=round(time.monotonic()-BEGAN,2),scope='closed-loop in-cluster HTTPS API diagnostic;2 private actors;2/4/8 readers;1-second per-reader period;no production capacity/SLA claim',readLatencies={k:summary(v) for k,v in LATENCIES.items()},statuses=dict(STATUSES),canonicalWrites=summary(WRITES) if WRITES else {},samples=SAMPLES,failures=FAILURES,activityTail=tail,foreignIsolation=True,revocationFresh=True,credentialsLogged=False)
print('LOAD_VERIFIED='+json.dumps(result),flush=True)
assert not FAILURES and sum(len(v) for v in LATENCIES.values())>DURATION and len(WRITES)>=DURATION//15,'Sustained workload failed or ended early'
