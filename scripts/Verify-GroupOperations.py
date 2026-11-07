import json,time,urllib.request,urllib.error
from pathlib import Path
url='http://brain-connect.brain-events.svc:8083'
config=json.loads(Path('/scripts/collaboration-outbox.json').read_text())
def request(path,method='GET',body=None):
 data=json.dumps(body).encode() if body is not None else None
 with urllib.request.urlopen(urllib.request.Request(url+path,data=data,method=method,headers={'Content-Type':'application/json'}),timeout=15) as r:return r.status,json.load(r)

_,names=request('/connectors')
expected={'identity-outbox','space-outbox','content-outbox','knowledge-outbox','learning-outbox','planning-outbox','media-outbox','notification-outbox','collaboration-outbox'}
assert expected.issubset(set(names)), 'Missing existing connector'
for name in sorted(expected):
 _,state=request('/connectors/'+name+'/status')
 assert state['connector']['state']=='RUNNING' and state['tasks'] and all(task['state']=='RUNNING' for task in state['tasks']),name+' not RUNNING'
 print('PASS '+name+' connector/task RUNNING (read-only status check)')
