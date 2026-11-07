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
a,space=browser();b,other=browser();suffix=uuid.uuid4().hex;base='/api/spaces/'+space+'/notes';title='Graph API '+suffix
source=expect(call(base,'POST',{'title':'Source '+suffix,'markdown':'[['+title+']]'},a),201)
await_graph(space,a,1,0)
target=expect(call(base,'POST',{'title':title,'markdown':'private target body'},a),201)
view=await_graph(space,a,2,1);assert set(view)=={'nodes','links','truncated'};assert all(set(n)=={'noteId','title'} for n in view['nodes']);assert view['links'][0]['sourceNoteId']==source['noteId'] and view['links'][0]['targetNoteId']==target['noteId'];assert set(view['links'][0])=={'linkId','sourceNoteId','targetNoteId'}
foreign=expect(call('/api/spaces/'+other+'/notes','POST',{'title':title,'markdown':'foreign body'},b),201);await_graph(other,b,1,0)
assert foreign['noteId'] not in {n['noteId'] for n in expect(graph(space,a),200)['nodes']}
denied(graph(other,a,headers={'X-Principal-Id':foreign['noteId'],'Authorization':'Bearer spoof'}),404);denied(graph(space,None),401);denied(graph(space,a+'; '+a),401)
for query in ['?limit=0','?limit=201','?limit=01','?limit=10&limit=20','?index=other','?q=anything']:assert graph(space,a,query)[0]==400
assert call('/api/spaces/'+space+'/graph/notes','POST',{},a)[0]==403
assert call('/internal/space/query-authorization','POST',{},a)[0]==403
assert expect(graph(space,a,'?limit=1'),200)['truncated'] is True
print('PASS HTTPS graph API, independent link resolution, bounded DTO, foreign Space/spoof/cookie/query/method denial',flush=True)
expect(call(base+'/'+target['noteId'],'PUT',{'title':'Renamed '+suffix,'markdown':'private target body'},a,{'If-Match':'"1"'}),200)
for _ in range(90):
 latest=expect(graph(space,a),200)
 if any(n['title']=='Renamed '+suffix for n in latest['nodes']):break
 time.sleep(1)
else:raise AssertionError('Rename did not converge')
assert latest['links']==view['links']
expect(call(base+'/'+target['noteId'],'DELETE',cookie=a,headers={'If-Match':'"2"'}),204);await_graph(space,a,1,0)
expect(call('/api/identity/guest-session/revoke','POST',{},a),204);denied(graph(space,a),401)
print('PASS stable link ID after rename, retirement convergence and immediate session revocation',flush=True)
page=call('/graph');assert page[0]==200 and b'Personal Brain' in page[2];assert 'no-store' in page[1].get('Cache-Control','');assert "frame-ancestors 'none'" in page[1].get('Content-Security-Policy','');assert "'unsafe-inline'" not in page[1].get('Content-Security-Policy','')
print('PASS deployed Next Graph View HTML with no-store and hashed inline-script CSP',flush=True)
if os.getenv('BRAIN_TEST_FAILURES')=='true':
 print('AUTHORITY_OUTAGE_READY',flush=True);marker('authority.down');denied(graph(other,b),503);print('AUTHORITY_OUTAGE_CONFIRMED',flush=True);marker('authority.up');await_graph(other,b,1,0)
 print('GRAPH_OUTAGE_READY',flush=True);marker('graph.down');denied(graph(other,b),503);print('GRAPH_OUTAGE_CONFIRMED',flush=True);marker('graph.up');await_graph(other,b,1,0)
 print('PASS real Space authority and Cassandra outages fail closed and recover',flush=True)
print('PASS private end-user graph API validation',flush=True)
