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
# Fixtures use ordinary authorized canonical writes. No source database access.
import urllib.error
for h in [{},{'Authorization':'Bearer '+'x'*43},{'Cookie':'guest=anything'}]:
 try:
  urllib.request.urlopen(urllib.request.Request('https://content-service.brain-apps.svc:8080/internal/content/note-snapshots',headers=h),context=context,timeout=8)
  raise AssertionError('Unauthenticated canonical snapshot accepted')
 except urllib.error.HTTPError as error:assert error.code==401
assert call('/internal/content/note-snapshots')[0] in (403,404)
print('PASS internal snapshot rejects browser/invalid credentials and is absent from Gateway',flush=True)
cookie,space=browser();base='/api/spaces/'+space+'/notes'
active=expect(call(base,'POST',{'title':'RebuildFixture','markdown':'canonical first'},cookie),201);path=base+'/'+active['noteId']
retired=expect(call(base,'POST',{'title':'RetiredRebuildFixture','markdown':'private retired text'},cookie),201);retired_path=base+'/'+retired['noteId']
expect(call(retired_path,'DELETE',cookie=cookie,headers={'If-Match':'"1"'}),204)
await_version(space,active['noteId'],1,'canonical first')
original_history=expect(call(path+'/revisions',cookie=cookie),200)
print('REBUILD_FIXTURES_READY',flush=True);marker('rebuild.snapshot')
expect(call(path,'PUT',{'title':'RebuiltLatest','markdown':'concurrent latest'},cookie,{'If-Match':'"1"'}),200)
created=expect(call(base,'POST',{'title':'ConcurrentRebuildCreate','markdown':'created during rebuild'},cookie),201)
third=expect(call(base,'POST',{'title':'ConcurrentRetire','markdown':'never searchable again'},cookie),201)
expect(call(base+'/'+third['noteId'],'DELETE',cookie=cookie,headers={'If-Match':'"1"'}),204)
print('REBUILD_CONCURRENT_WRITES_DONE',flush=True);marker('rebuild.complete')
await_version(space,active['noteId'],2,'concurrent latest');await_version(space,created['noteId'],1,'created during rebuild')
assert hits(space,retired['noteId'])['total']['value']==0
for _ in range(60):
 if hits(space,third['noteId'])['total']['value']==0:break
 time.sleep(1)
else:raise AssertionError('Concurrent retirement resurrected')
assert expect(call(path,cookie=cookie),200)['markdown']=='concurrent latest'
assert len(expect(call(path+'/revisions',cookie=cookie),200))==len(original_history)+1
assert call(retired_path,cookie=cookie)[0]==404
print('PASS lost derived index rebuilt from canonical snapshots and concurrent create/update/retire Kafka tail',flush=True)
print('PASS PRIVATE active text/version restored, retired notes excluded and canonical history preserved',flush=True)
