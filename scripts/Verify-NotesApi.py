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
for _ in range(45):
 try:
  with urllib.request.urlopen(origin+'/actuator/health/readiness',context=context,timeout=5) as probe:
   if probe.status==200:break
 except ssl.SSLCertVerificationError:raise
 except Exception:time.sleep(1)
else:raise AssertionError('Gateway readiness unavailable')
a,space=browser();b,other=browser();base='/api/spaces/'+space+'/notes';otherbase='/api/spaces/'+other+'/notes'
expect(call(base),401);expect(call(base,cookie=b,headers={'X-Principal-Id':'spoof'}),404)
trace='0123456789abcdef0123456789abcdef';parent='00-'+trace+'-123456789abcdef0-01'
result=call(base,'POST',{'title':'공부 기록','markdown':'# Markdown\n[[Wiki Link]]\n<script>plain stored text</script>'},a,{'traceparent':parent});note=expect(result,201);etag=result[1]['ETag'];path=base+'/'+note['noteId']
assert note['visibility']=='PRIVATE' and note['state']=='ACTIVE' and note['version']==1 and etag=='"1"'
assert result[1]['Location']==path and result[1]['X-Trace-Id']==trace
expect(call(path,cookie=b),404);expect(call(otherbase+'/'+note['noteId'],cookie=b),404)
expect(call('/internal/space/content-authorization','POST',{},a),403)
expect(call(path,'PUT',{'title':'bad','markdown':''},a),428)
expect(call(path,'PUT',{'title':'bad','markdown':''},a,{'If-Match':'W/"1"'}),400)
expect(call(path,'PUT',{'title':'bad','markdown':''},a,{'If-Match':etag,'Origin':'https://evil.example'}),403)
expect(call(base,'POST',{'title':'bad','markdown':'가'*11000},a),400)
expect(call(base,'POST',{'title':'bad','markdown':'','visibility':'PUBLIC'},a),400)
expect(call(base,'POST',{'title':'large','markdown':'가'*10000},a),201)
page=expect(call(base+'?limit=1',cookie=a),200);assert len(page['items'])==1 and page['nextAfter'];assert len(expect(call(base+'?limit=1&after='+page['nextAfter'],cookie=a),200)['items'])==1
expect(call(base+'?url=https://evil.example',cookie=a),400)
print('PASS HTTPS guest identity, private Markdown CRUD setup, cross-Space isolation, Origin, bounds and safe pagination',flush=True)
updated=expect(call(path,'PUT',{'title':'수정','markdown':'**내용**'},a,{'If-Match':etag}),200);assert updated['version']==2
expect(call(path,'PUT',{'title':'stale','markdown':'lost'},a,{'If-Match':etag}),412)
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:
 results=list(workers.map(lambda title:call(path,'PUT',{'title':title,'markdown':'winner'},a,{'If-Match':'"2"'}),['editor one','editor two']))
assert sorted(x[0] for x in results)==[200,412]
history=expect(call(path+'/revisions?limit=10',cookie=a),200);assert [x['version'] for x in history]==[3,2,1];assert history[-1]['markdown'].startswith('# Markdown')
assert [x['version'] for x in expect(call(path+'/revisions?before=3&limit=1',cookie=a),200)]==[2]
print('PASS If-Match required, stale edit rejected, simultaneous edit has one winner, immutable revision snapshots and pagination',flush=True)
if os.getenv('BRAIN_TEST_RESTART')=='true':
 print('PHASE1_WAITING_FOR_RESTART',flush=True);end=time.monotonic()+300;seen=set()
 while time.monotonic()<end and not Path('/tmp/restart.complete').exists():
  for service in ('space','identity','content'):
   flag=Path('/tmp/'+service+'.down')
   if flag.exists() and service not in seen:
    result=call(path,'PUT',{'title':'must fail','markdown':'no mutation'},a,{'If-Match':'"3"'})
    assert result[0] in ((502,503,504) if service=='content' else (503,504)),service+' outage '+str(result[0]);seen.add(service);print('PASS '+service+' outage fails closed status='+str(result[0]),flush=True);flag.unlink()
  time.sleep(1)
 assert Path('/tmp/restart.complete').exists(),'Restart marker timeout'
 restored=expect(call(path,cookie=a),200);assert restored['version']==3 and restored['spaceId']==space and restored['noteId']==note['noteId'];assert len(expect(call(path+'/revisions',cookie=a),200))==3
 print('PASS Content restart retains identical Note, Space, version and history; outage writes made no mutation',flush=True)
expect(call(path,'DELETE',cookie=a,headers={'If-Match':'"3"'}),204);expect(call(path,cookie=a),404)
history=expect(call(path+'/revisions?limit=10',cookie=a),200);assert [x['version'] for x in history]==[4,3,2,1] and history[0]['state']=='RETIRED'
assert all(x['noteId']!=note['noteId'] for x in expect(call(base,cookie=a),200)['items'])
expect(call(path+'/revisions',cookie=b),404)
expect(call('/api/identity/guest-session/revoke','POST',{},a),204);expect(call(base,cookie=a),401);expect(call(otherbase,cookie=b),200)
print('PASS soft deletion keeps private revisions, hides active reads/list, and guest revocation immediately denies Notes while other browser stays active',flush=True)
