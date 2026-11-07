"""Local common-origin HTTPS validation; browser credentials stay only in process memory."""
import json,ssl,time,urllib.request,urllib.error,concurrent.futures,os
from pathlib import Path
origin='https://gateway-service.brain-apps.svc:8080'
context=ssl.create_default_context(cafile='/trust/ca.crt')
def call(path,method='GET',body=None,cookie=None,headers=None):
 h={'Content-Type':'application/json','Origin':'https://localhost:8443','Sec-Fetch-Site':'same-origin'}
 if cookie:h['Cookie']=cookie
 h.update(headers or {})
 payload=json.dumps(body,ensure_ascii=False).encode() if body is not None else None
 try:
  with urllib.request.urlopen(urllib.request.Request(origin+path,data=payload,method=method,headers=h),context=context,timeout=15) as response:return response.status,dict(response.headers),response.read()
 except urllib.error.HTTPError as error:return error.code,dict(error.headers),error.read()
def expect(result,status):
 assert result[0]==status,'Expected '+str(status)+' got '+str(result[0]);assert 'no-store' in result[1].get('Cache-Control','');return json.loads(result[2]) if result[2] else None
def browser():
 result=call('/api/identity/guest-session','POST',{});expect(result,201);cookie=result[1]['Set-Cookie'].split(';')[0]
 for _ in range(60):
  result=call('/api/spaces/personal',cookie=cookie)
  if result[0]==200:return cookie,expect(result,200)['spaceId']
  assert result[0]==202;time.sleep(1)
 raise AssertionError('Space not provisioned')

import uuid
search_origin='https://brain-opensearch.brain-data.svc:9200'
search_context=ssl.create_default_context(cafile='/search-reader/ca.crt')
search_context.load_cert_chain('/search-reader/tls.crt','/search-reader/tls.key')
def hits(space,note):
 query={'query':{'bool':{'filter':[{'term':{'spaceId':space}},{'term':{'noteId':note}},{'term':{'visibility':'PRIVATE'}},{'term':{'state':'ACTIVE'}}]}},'version':True}
 request=urllib.request.Request(search_origin+'/brain-notes-v1/_search',data=json.dumps(query).encode(),headers={'Content-Type':'application/json'},method='POST')
 with urllib.request.urlopen(request,context=search_context,timeout=8) as response:
  result=json.load(response);assert not result['timed_out'] and result['_shards']['failed']==0;return result['hits']
def await_version(space,note,version,body):
 for i in range(90):
  result=hits(space,note)
  if result['total']['value']==1 and result['hits'][0]['_version']==version:
   assert result['hits'][0]['_source']['markdown']==body;return
  time.sleep(1)
 raise RuntimeError('Note event did not project to expected version')
def marker(name):
 for i in range(180):
  if Path('/tmp/'+name).exists():return
  time.sleep(1)
 raise RuntimeError('Operator handshake timeout')
cookie,space=browser();_,other=browser();base='/api/spaces/'+space+'/notes'
note=expect(call(base,'POST',{'title':'PipelineFixture '+uuid.uuid4().hex,'markdown':'first body'},cookie),201)
path=base+'/'+note['noteId'];await_version(space,note['noteId'],1,'first body')
assert hits(other,note['noteId'])['total']['value']==0
expect(call(path,'PUT',{'title':'UpdatedFixture','markdown':'second body'},cookie,{'If-Match':'"1"'}),200)
await_version(space,note['noteId'],2,'second body')
print('PASS real HTTPS Content -> Outbox -> Debezium -> Kafka -> Brain Query Inbox -> OpenSearch create/update and Space-scoped results',flush=True)
if os.getenv('BRAIN_TEST_RESTART')=='true':
 print('PROJECTOR_OUTAGE_READY',flush=True);marker('projector.down')
 expect(call(path,'PUT',{'title':'OfflineFixture','markdown':'third body'},cookie,{'If-Match':'"2"'}),200)
 time.sleep(2);assert hits(space,note['noteId'])['hits'][0]['_version']==2
 print('PROJECTOR_OUTAGE_WRITE_DONE',flush=True);marker('projector.up')
 await_version(space,note['noteId'],3,'third body')
 print('PASS replaced Brain Query Pod catches up an event written while consumer was stopped',flush=True)
 print('SEARCH_OUTAGE_READY',flush=True);marker('search.down')
 expect(call(path,'PUT',{'title':'StorageOfflineFixture','markdown':'fourth body'},cookie,{'If-Match':'"3"'}),200)
 for i in range(30):
  try:
   with urllib.request.urlopen('https://brain-query-service.brain-apps.svc:8080/actuator/health/readiness',context=context,timeout=5) as response:pass
  except urllib.error.HTTPError as error:
   if error.code==503:break
  time.sleep(1)
 else:raise RuntimeError('Projector did not become unready during storage outage')
 print('SEARCH_OUTAGE_WRITE_DONE',flush=True);marker('search.up')
 await_version(space,note['noteId'],4,'fourth body')
 print('PASS OpenSearch outage leaves durable retry state; restoration projects the pending revision',flush=True)
 version=4
else:version=2
expect(call(path,'DELETE',cookie=cookie,headers={'If-Match':'"'+str(version)+'"'}),204)
for i in range(90):
 if hits(space,note['noteId'])['total']['value']==0:break
 time.sleep(1)
else:raise RuntimeError('Retired note remained searchable')
print('PASS actual NoteRetired event removes private note from search results',flush=True)
