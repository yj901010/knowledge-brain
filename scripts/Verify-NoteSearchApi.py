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

import uuid,urllib.parse,os
def search(space,text,cookie,offset=0,limit=20,headers=None):
 return call('/api/spaces/'+space+'/search/notes?'+urllib.parse.urlencode({'q':text,'offset':offset,'limit':limit}),cookie=cookie,headers=headers)
def await_total(space,text,cookie,total):
 for _ in range(90):
  result=expect(search(space,text,cookie),200)
  if result['total']==total:return result
  time.sleep(1)
 raise AssertionError('Search projection did not reach expected authorized total')
def no_results(result,status):
 data=expect(result,status);assert set(data)=={'error'};assert 'total' not in data and 'items' not in data

def marker(name):
 for _ in range(180):
  if Path('/tmp/'+name).exists():return
  time.sleep(1)
 raise RuntimeError('Operator handshake timeout')
a,space=browser();b,other=browser();needle='SearchFixture'+uuid.uuid4().hex;base='/api/spaces/'+space+'/notes'
first=expect(call(base,'POST',{'title':needle+' \uacf5\ubd80 Alpha','markdown':'owner private body'},a),201)
second=expect(call(base,'POST',{'title':'Beta','markdown':needle+' private Markdown match'},a),201)
foreign=expect(call('/api/spaces/'+other+'/notes','POST',{'title':needle+' Foreign','markdown':'other private body'},b),201)
found=await_total(space,needle,a,2);await_total(other,needle,b,1)
assert await_total(space,needle+' \uacf5\ubd80',a,1)['items'][0]['noteId']==first['noteId']
assert expect(search(space,'{"match_all":{}}',a),200)['total']==0
print('PASS encoded Korean query and literal DSL-shaped text use plain analysis',flush=True)
assert {item['noteId'] for item in found['items']}=={first['noteId'],second['noteId']}
assert all(set(item)=={'noteId','title'} for item in found['items'])
page=expect(search(space,needle,a,limit=1),200);assert page['total']==2 and page['nextOffset']==1
next_page=expect(search(space,needle,a,offset=1,limit=1),200);assert next_page['total']==2 and next_page['nextOffset'] is None and page['items'][0]['noteId']!=next_page['items'][0]['noteId']
no_results(search(other,needle,a,headers={'X-Principal-Id':foreign['noteId'],'Authorization':'Bearer spoof'}),404)
no_results(search(space,needle,None),401)
no_results(search(space,needle,a+'; '+a),401)
assert call('/api/spaces/'+space+'/search/notes?q=a&index=other',cookie=a)[0]==400
assert call('/api/spaces/'+space+'/search/notes?q=a&q=b',cookie=a)[0]==400
assert call('/api/spaces/'+space+'/search/notes','POST',{},a)[0]==403
assert call('/internal/space/query-authorization','POST',{},a)[0]==403
print('PASS common-origin HTTPS title/Markdown search, exact private totals and bounded pagination',flush=True)
print('PASS foreign Space, spoofed principal/bearer, absent/duplicate cookie and undeclared methods/parameters denied without hits/counts',flush=True)
changed='EditedSearch'+uuid.uuid4().hex
expect(call(base+'/'+first['noteId'],'PUT',{'title':changed,'markdown':'latest body'},a,{'If-Match':'"1"'}),200)
await_total(space,needle,a,1);latest=await_total(space,changed,a,1);assert latest['items'][0]['title']==changed
expect(call(base+'/'+second['noteId'],'DELETE',cookie=a,headers={'If-Match':'"1"'}),204);await_total(space,needle,a,0)
print('PASS actual canonical edit and retirement converge through CDC/Kafka to the user search API',flush=True)
expect(call('/api/identity/guest-session/revoke','POST',{},a),204)
no_results(search(space,changed,a),401)
print('PASS a previously successful guest is denied immediately after session revocation',flush=True)
if os.getenv('BRAIN_TEST_FAILURES')=='true':
 print('AUTHORITY_OUTAGE_READY',flush=True);marker('authority.down')
 no_results(search(other,needle,b),503);print('AUTHORITY_OUTAGE_CONFIRMED',flush=True);marker('authority.up')
 await_total(other,needle,b,1);print('PASS Space authority outage fails closed and replaced Pod restores authorized search',flush=True)
 print('SEARCH_OUTAGE_READY',flush=True);marker('search.down')
 no_results(search(other,needle,b),503);print('SEARCH_OUTAGE_CONFIRMED',flush=True);marker('search.up')
 await_total(other,needle,b,1);print('PASS OpenSearch outage returns generic 503 without partial private results and recovers',flush=True)
print('PASS private end-user search API validation',flush=True)
