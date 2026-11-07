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

a,space=browser();b,other=browser();base='/api/spaces/'+space;notes=base+'/notes'
note=expect(call(notes,'POST',{'title':'Metadata test','markdown':'body'},a),201);path=notes+'/'+note['noteId'];meta=path+'/metadata'
initial=expect(call(meta,cookie=a),200);assert initial['metadataVersion']==0 and initial['tags']==[] and initial['properties']==[]
edit={'tags':[' study ','개발'],'properties':{'score':{'type':'NUMBER','value':3.125},'done':{'type':'BOOLEAN','value':True},'date':{'type':'DATE','value':'2028-02-29'},'text':{'type':'STRING','value':'메모'},'list':{'type':'STRING_LIST','value':['one','two']}}}
expect(call(meta,'PUT',edit,a),428);expect(call(meta,'PUT',edit,a,{'If-Match':'W/"m0"'}),400);expect(call(meta,'PUT',edit,a,{'If-Match':'"m0"','Origin':'https://evil.example'}),403)
for endpoint in (meta,base+'/tags',base+'/property-definitions',meta+'/revisions'):
 expect(call(endpoint,cookie=b),404);expect(call(endpoint),401)
result=call(meta,'PUT',edit,a,{'If-Match':'"m0"'});first=expect(result,200);assert result[1]['ETag']=='"m1"';assert sorted(x['name'] for x in first['tags'])==['study','개발'];assert {x['name']:x['value'] for x in first['properties']}=={x:y['value'] for x,y in edit['properties'].items()}
expect(call(meta,'PUT',edit,a,{'If-Match':'"m0"'}),412)
for field in ({'type':'DATE','value':'2026-02-29'},{'type':'NUMBER','value':'3'},{'type':'BOOLEAN','value':None}):
 expect(call(meta,'PUT',{'tags':[],'properties':{'bad':field}},a,{'If-Match':'"m1"'}),400)
conflict={'tags':['lost'],'properties':{'score':{'type':'STRING','value':'bad'}}};expect(call(meta,'PUT',conflict,a,{'If-Match':'"m1"'}),409);assert expect(call(meta,cookie=a),200)==first
assert len(expect(call(base+'/tags',cookie=a),200)['items'])==2;assert len(expect(call(base+'/property-definitions',cookie=a),200)['items'])==5
page=expect(call(base+'/tags?limit=1',cookie=a),200);assert page['nextAfter'];assert len(expect(call(base+'/tags?limit=1&after='+page['nextAfter'],cookie=a),200)['items'])==1
expect(call(base+'/tags?evil=1',cookie=a),400);expect(call(meta+'?limit=1',cookie=a),400)
expect(call(path,'PUT',{'title':'New title','markdown':'new body'},a,{'If-Match':'"1"'}),200);assert expect(call(meta,cookie=a),200)==first
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:
 results=list(workers.map(lambda _:call(meta,'PUT',edit,a,{'If-Match':'"m1"'}),range(2)))
assert sorted(x[0] for x in results)==[200,412];saved=expect(call(meta,cookie=a),200);assert saved['metadataVersion']==2 and saved['tags']==first['tags'] and saved['properties']==first['properties']
history=expect(call(meta+'/revisions?limit=10',cookie=a),200);assert [x['metadataVersion'] for x in history]==[2,1];assert expect(call(meta+'/revisions?before=2&limit=1',cookie=a),200)[0]['metadataVersion']==1
print('PASS HTTPS typed properties, canonical stable tags, Space privacy, Origin, metadata ETags, validation, schema conflict rollback, pagination and concurrent writers',flush=True)
if os.getenv('BRAIN_TEST_RESTART')=='true':
 print('PHASE1_WAITING_FOR_RESTART',flush=True);end=time.monotonic()+240
 while time.monotonic()<end and not Path('/tmp/restart.complete').exists():time.sleep(1)
 assert Path('/tmp/restart.complete').exists(),'Restart marker timeout'
 assert expect(call(meta,cookie=a),200)==saved;assert expect(call(meta+'/revisions?limit=10',cookie=a),200)==history
 print('PASS Content Pod replacement preserves exact metadata IDs, typed values, versions and revision snapshots',flush=True)
cleared=expect(call(meta,'PUT',{'tags':[],'properties':{}},a,{'If-Match':'"m2"'}),200);assert cleared['metadataVersion']==3 and cleared['tags']==[] and cleared['properties']==[]
assert all(x['activeNoteCount']==0 for x in expect(call(base+'/tags',cookie=a),200)['items'])
expect(call(path,'DELETE',cookie=a,headers={'If-Match':'"2"'}),204);expect(call(meta,cookie=a),404);retired=expect(call(meta+'/revisions?limit=10',cookie=a),200);assert [x['metadataVersion'] for x in retired]==[4,3,2,1]
expect(call('/api/identity/guest-session/revoke','POST',{},a),204);expect(call(base+'/tags',cookie=a),401);expect(call('/api/spaces/'+other+'/tags',cookie=b),200)
print('PASS clear retains definitions with zero usage; retirement retains private history; revocation fails closed',flush=True)
