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

def marker(name):
 for _ in range(180):
  if Path('/tmp/'+name).exists():return
  time.sleep(1)
 raise RuntimeError('Operator handshake timeout')
import uuid

def graph(space,cookie,query='?limit=200',headers=None):return call('/api/spaces/'+space+'/graph/notes'+query,cookie=cookie,headers=headers)
def await_graph(space,cookie,nodes,links):
 for _ in range(90):
  data=expect(graph(space,cookie),200)
  if len(data['nodes'])==nodes and len(data['links'])==links:return data
  time.sleep(1)
 raise AssertionError('Authorized graph did not converge')
def denied(result,status):
 data=expect(result,status);assert set(data)=={'error'} and not any(k in data for k in ['nodes','links','truncated'])
a,space=browser();b,other=browser();suffix=uuid.uuid4().hex
notes='/api/spaces/'+space+'/notes';catalog='/api/spaces/'+space+'/concepts'
note=expect(call(notes,'POST',{'title':'Concept link '+suffix,'markdown':'Private body'},a),201);path=notes+'/'+note['noteId']+'/concepts'
concept=expect(call(catalog,'POST',{'title':'Concept '+suffix,'description':'private concept definition'},a),201);cid=concept['conceptId'];assert concept['visibility']=='PRIVATE'
assert expect(call(path,cookie=a),200)['conceptVersion']==0
expect(call(path,'PUT',{'conceptIds':[cid]},a,{'If-Match':'"c0"'}),200)
first=expect(call(path,cookie=a),200);assert first['items'][0]['conceptId']==cid and set(first['items'][0])=={'linkId','conceptId'}
assert call(path,'PUT',{'conceptIds':[]},a,{'If-Match':'"c0"'})[0]==412
assert call(path,'PUT',{'conceptIds':[cid,cid]},a,{'If-Match':'"c1"'})[0]==400
assert call(path,'PUT',{'conceptIds':[]},a)[0]==428
assert call(path,'PUT',{'conceptIds':[]},a,{'If-Match':'"c1"','Origin':'https://evil.example'})[0]==403
assert call(catalog+'/'+cid,cookie=b,headers={'X-Principal-Id':concept['conceptId']})[0]==404
foreign=expect(call('/api/spaces/'+other+'/concepts','POST',{'title':'foreign','description':'foreign private'},b),201)
assert call(path,'PUT',{'conceptIds':[foreign['conceptId']]},a,{'If-Match':'"c1"'})[0]==404
assert expect(call(path,cookie=a),200)==first
renamed=expect(call(catalog+'/'+cid,'PUT',{'title':'Renamed '+suffix,'description':'definition remains Knowledge-owned'},a,{'If-Match':'"1"'}),200)
assert expect(call(path,cookie=a),200)==first
assert len(expect(call(catalog+'/'+cid+'/revisions',cookie=a),200))==2
expect(call(catalog+'/'+cid,'DELETE',cookie=a,headers={'If-Match':'"2"'}),204)
assert call(catalog+'/'+cid,cookie=a)[0]==404
assert expect(call(path,cookie=a),200)==first
expect(call(path,'PUT',{'conceptIds':[]},a,{'If-Match':'"c1"'}),200)
assert call(path,'PUT',{'conceptIds':[cid]},a,{'If-Match':'"c2"'})[0]==404
active=expect(call(catalog,'POST',{'title':'Active '+suffix,'description':''},a),201)
expect(call(path,'PUT',{'conceptIds':[active['conceptId']]},a,{'If-Match':'"c2"'}),200)
assert len(expect(call(path+'/revisions',cookie=a),200))==3
print('PASS private Concept CRUD/revisions, Content-only stable ID refs, ETags, foreign Space, duplicate/origin/missing precondition denial and retired Concept audit',flush=True)
public_path=None
if os.getenv('BRAIN_TEST_PUBLIC_REFERENCES')=='true':
 public_spaces=expect(call('/api/spaces/public',cookie=a),200)['items'];public_id=None
 for public_space in public_spaces:
  published=expect(call('/api/public/spaces/'+public_space['spaceId']+'/concepts?limit=20',cookie=a),200)['items']
  if published:public_id=published[0]['conceptId'];break
 assert public_id,'Create a canonical public fixture through contribution/review/merge before this optional validation'
 public_note=expect(call(notes,'POST',{'title':'Public reference '+suffix,'markdown':'Still private'},a),201);public_path=notes+'/'+public_note['noteId']+'/concepts'
 public_before=expect(call(public_path,'PUT',{'conceptIds':[public_id,active['conceptId']]},a,{'If-Match':'"c0"'}),200)
 assert set(public_before['items'][0])=={'linkId','conceptId'}
 assert call(public_path,cookie=b)[0]==404
 print('PASS guest mixed public/private ID references and private Note isolation',flush=True)
if os.getenv('BRAIN_TEST_FAILURES')=='true':
 new=expect(call(catalog,'POST',{'title':'Outage '+suffix,'description':''},a),201);before=expect(call(path,cookie=a),200)
 print('AUTHORITY_OUTAGE_READY',flush=True);marker('authority.down')
 assert call(path,'PUT',{'conceptIds':[new['conceptId']]},a,{'If-Match':'"c3"'})[0]==503
 assert expect(call(path,cookie=a),200)==before
 if public_path:
  assert expect(call(public_path,cookie=a),200)==public_before
  retained=expect(call(public_path,'PUT',{'conceptIds':[active['conceptId'],public_id]},a,{'If-Match':'"c1"'}),200)
  assert retained['items'][1]['linkId']==public_before['items'][0]['linkId']
  expect(call(public_path,'PUT',{'conceptIds':[]},a,{'If-Match':'"c2"'}),200)
  assert call(public_path,'PUT',{'conceptIds':[public_id]},a,{'If-Match':'"c3"'})[0]==503
  assert expect(call(public_path,cookie=a),200)['items']==[]
  print('PASS public refs remain readable/reorderable/removable during real Knowledge outage; new addition fails closed',flush=True)
 print('AUTHORITY_OUTAGE_CONFIRMED',flush=True);marker('authority.up')
 expect(call(path,'PUT',{'conceptIds':[new['conceptId']]},a,{'If-Match':'"c3"'}),200)
 if public_path:expect(call(public_path,'PUT',{'conceptIds':[public_id]},a,{'If-Match':'"c3"'}),200)
 print('PASS real Knowledge outage prevents additions without mutating links and recovers',flush=True)
current=expect(call(path,cookie=a),200);expect(call(notes+'/'+note['noteId'],'DELETE',cookie=a,headers={'If-Match':'"1"'}),204)
assert call(path,cookie=a)[0]==404;history=expect(call(path+'/revisions',cookie=a),200);assert history[0]['snapshot']['items']==[]
expect(call('/api/identity/guest-session/revoke','POST',{},a),204);assert call(catalog,cookie=a)[0]==401
assert call('/internal/knowledge/concept-check','POST',{'spaceId':space,'conceptIds':[]},b)[0]==403
expect(call('/api/identity/guest-session/revoke','POST',{},b),204)
print('PASS Note retirement emits cleared reference revision, immediate revocation and internal route denial',flush=True)
