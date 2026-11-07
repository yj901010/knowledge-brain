import json,time,urllib.request,urllib.error
from pathlib import Path
url='http://brain-connect.brain-events.svc:8083'
config=json.loads(Path('/scripts/media-outbox.json').read_text())
def request(path,method='GET',body=None):
 data=json.dumps(body).encode() if body is not None else None
 with urllib.request.urlopen(urllib.request.Request(url+path,data=data,method=method,headers={'Content-Type':'application/json'}),timeout=15) as r:return r.status,json.load(r)
_,plugins=request('/connector-plugins')
assert any(p['class']==config['connector.class'] for p in plugins),'PostgreSQL plugin missing'
status,_=request('/connectors/media-outbox/config','PUT',config)
assert status in (200,201)
for _ in range(45):
 try:
  _,status=request('/connectors/media-outbox/status')
 except urllib.error.HTTPError as error:
  if error.code in (404,409):
   time.sleep(2);continue
  raise
 if status['connector']['state']=='RUNNING' and status['tasks'] and all(t['state']=='RUNNING' for t in status['tasks']):
  print('PASS media-outbox connector and task RUNNING',flush=True);break
 if any(t['state']=='FAILED' for t in status.get('tasks',[])):raise RuntimeError('Connector task failed; inspect worker logs')
 time.sleep(2)
else:raise RuntimeError('Connector/task readiness timeout')
