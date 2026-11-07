"""Metadata-only operator metrics; no SQL, Secret reads, connector writes or payloads."""
import json,os,ssl,threading,time,urllib.request
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path

SERVICES=['gateway','identity','space','knowledge','content','learning','planning','collaboration','activity','media','integration','notification','brain-query']
NAMESPACES=['brain-apps','brain-data','brain-events','brain-ops','brain-observability']
OWNERS=['identity','space','knowledge','content','learning','planning','collaboration','media','integration','notification']
KUBE='https://kubernetes.default.svc'
TOKEN=Path('/var/run/secrets/kubernetes.io/serviceaccount/token')
TLS=ssl.create_default_context(cafile='/var/run/secrets/kubernetes.io/serviceaccount/ca.crt')
snapshot=b'';last_success=0;lock=threading.Lock()

def fetch(path):
 request=urllib.request.Request(KUBE+path,headers={'Authorization':'Bearer '+TOKEN.read_text().strip()})
 with urllib.request.urlopen(request,context=TLS,timeout=3) as response:return json.load(response)

def label(value):return str(value).replace('\\','\\\\').replace('"','\\"').replace('\n','\\n')

def collect():
 global snapshot,last_success
 rows=[]
 def metric(name,value,**labels):
  suffix='{'+','.join(k+'="'+label(v)+'"' for k,v in sorted(labels.items()))+'}' if labels else ''
  rows.append(name+suffix+' '+str(value))
 apps={};restarts={};kube_ok=1
 try:
  for ns in NAMESPACES:
   for kind in ['deployments','statefulsets']:
    for item in fetch('/apis/apps/v1/namespaces/'+ns+'/'+kind)['items']:
     name=item['metadata']['name'];ready=item.get('status',{}).get('readyReplicas',0)
     metric('brain_workload_ready_replicas',ready,namespace=ns,workload=name,kind=kind)
     metric('brain_workload_desired_replicas',item['spec'].get('replicas',1),namespace=ns,workload=name,kind=kind)
     if ns=='brain-apps':apps[name]=ready
   if ns=='brain-apps':
    for pod in fetch('/api/v1/namespaces/brain-apps/pods')['items']:
     service=pod['metadata'].get('labels',{}).get('app.kubernetes.io/name','')
     if service in [s+'-service' for s in SERVICES]+['brain-login']:
      restarts[service]=restarts.get(service,0)+sum(c.get('restartCount',0) for c in pod.get('status',{}).get('containerStatuses',[]))
 except Exception:kube_ok=0
 metric('brain_observer_collection_success',kube_ok,target='kubernetes')
 for name in [s+'-service' for s in SERVICES]+['brain-login']:
  metric('brain_application_ready',apps.get(name,0),service_name=name)
  metric('brain_application_restart_count',restarts.get(name,0),service_name=name)
  if name!='brain-login':metric('brain_expected_application_info',1,service_name=name)
 connect={};connect_ok=1
 try:
  with urllib.request.urlopen('http://brain-connect.brain-events.svc:8083/connectors?expand=status',timeout=3) as response:connect=json.load(response)
 except Exception:connect_ok=0
 metric('brain_observer_collection_success',connect_ok,target='connect')
 for owner in OWNERS:
  name=owner+'-outbox';state=connect.get(name,{}).get('status',{})
  running=state.get('connector',{}).get('state')=='RUNNING'
  tasks=state.get('tasks',[])
  metric('brain_cdc_connector_running',int(running),connector=name)
  metric('brain_cdc_tasks_running',int(bool(tasks) and all(t['state']=='RUNNING' for t in tasks)),connector=name)
 try:
  memory={line.split(':',1)[0]:int(line.split(':',1)[1].split()[0])*1024 for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith(('MemTotal:','MemAvailable:','SwapTotal:','SwapFree:'))}
  for name,field in [('memory_total','MemTotal'),('memory_available','MemAvailable'),('swap_total','SwapTotal'),('swap_free','SwapFree')]:metric('brain_host_'+name+'_bytes',memory[field])
 except Exception:metric('brain_observer_collection_success',0,target='host_memory')
 metric('brain_observer_last_collection_timestamp_seconds',time.time())
 with lock:snapshot=('\n'.join(rows)+'\n').encode();last_success=time.monotonic()

def loop():
 while True:
  try:collect()
  except Exception:pass
  time.sleep(10)

class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  if self.path=='/healthz':
   with lock:healthy=bool(snapshot) and time.monotonic()-last_success<60
   self.send_response(200 if healthy else 503);self.end_headers();self.wfile.write(b'operator-observer');return
  if self.path!='/metrics':self.send_response(404);self.end_headers();return
  with lock:body=snapshot
  self.send_response(200);self.send_header('Content-Type','text/plain; version=0.0.4');self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(body)

threading.Thread(target=loop,daemon=True).start()
ThreadingHTTPServer(('0.0.0.0',9102),Handler).serve_forever()
