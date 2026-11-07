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
def create(title,markdown='',where=None,cookie=None):return expect(call(where or base,'POST',{'title':title,'markdown':markdown},cookie or a),201)
def path(note,where=None):return (where or base)+'/'+note['noteId']
def links(note,cookie=None,where=None,query=''):return expect(call(path(note,where)+'/links'+query,cookie=cookie or a),200)
def backlinks(note,cookie=None,where=None,query=''):return expect(call(path(note,where)+'/backlinks'+query,cookie=cookie or a),200)
def update(note,title,markdown):return expect(call(path(note),'PUT',{'title':title,'markdown':markdown},a,{'If-Match':'"'+str(note['version'])+'"'}),200)
target=create('Target');source=create('Source','[[Target#section|shown]] [[Future]]\n```java\n[[ignored]]\n```\n`[[also ignored]]`')
out=links(source);assert len(out['items'])==2 and [x['status'] for x in out['items']]==['RESOLVED','UNRESOLVED'];stable=out['items'][0]['linkId'];assert out['items'][0]['targetNoteId']==target['noteId'] and out['items'][0]['fragment']=='section' and out['items'][0]['label']=='shown'
assert backlinks(target)['items'][0]['sourceNoteId']==source['noteId']
expect(call(path(source)+'/links',cookie=b,headers={'X-Principal-Id':'spoof'}),404);expect(call(path(target)+'/backlinks',cookie=b),404);expect(call(otherbase+'/'+target['noteId']+'/backlinks',cookie=b),404)
page=links(source,query='?limit=1');assert page['nextAfter']==0 and len(links(source,query='?after=0&limit=1')['items'])==1
expect(call(path(source)+'/links?after=-1',cookie=a),400);expect(call(path(target)+'/backlinks?after=0',cookie=a),400)
target=update(target,'Renamed','');source=update(source,'Source','prefix [[Target#other|new label]] [[Future]]');create('Target');out=links(source);assert out['items'][0]['linkId']==stable and out['items'][0]['targetNoteId']==target['noteId'] and out['items'][0]['targetTitle']=='Renamed'
future=create('Future');out=links(source);assert out['items'][1]['targetNoteId']==future['noteId'];assert out['noteVersion']==2 and out['linkVersion']>=3
print('PASS real Wiki parsing, alias/fragment, code exclusions, pagination, stable rename/edit binding, future-note resolution and unchanged source version',flush=True)
duplicate1=create('Same');duplicate2=create('Same');ambiguous=create('Ambiguous','[[Same]] [[note:'+duplicate2['noteId']+'|chosen]]');out=links(ambiguous);assert out['items'][0]['status']=='AMBIGUOUS' and out['items'][1]['targetNoteId']==duplicate2['noteId']
private=create('Secret',where=otherbase,cookie=b);cross=create('Cross','[[Secret]] [[note:'+private['noteId']+']]');assert all(x['status']=='UNRESOLVED' and x['targetTitle'] is None and x['targetNoteId'] is None for x in links(cross)['items'])
expect(call(path(source)+'/links','POST',{},a),403);expect(call(path(source)+'/links?url=https://evil.example',cookie=a),400)
before=out['linkVersion'];expect(call(path(duplicate1),'DELETE',cookie=a,headers={'If-Match':'"1"'}),204);assert links(ambiguous)['items'][0]['targetNoteId']==duplicate2['noteId']
print('PASS duplicate-title ambiguity, explicit stable-ID selection, cross-Space privacy and declared read-only Gateway routes',flush=True)
if os.getenv('BRAIN_TEST_RESTART')=='true':
 expected=links(source);print('PHASE1_WAITING_FOR_RESTART',flush=True);end=time.monotonic()+240
 while time.monotonic()<end and not Path('/tmp/restart.complete').exists():time.sleep(1)
 assert Path('/tmp/restart.complete').exists(),'Restart marker timeout';restored=links(source);assert restored==expected;assert backlinks(target)['items'][0]['sourceNoteId']==source['noteId']
 print('PASS Content replacement preserves link IDs, target IDs, source/link versions and backlinks without duplicate backfill',flush=True)
expect(call(path(target),'DELETE',cookie=a,headers={'If-Match':'"2"'}),204);assert links(source)['items'][0]['status']=='TARGET_RETIRED' and links(source)['items'][0]['targetNoteId']==target['noteId'];expect(call(path(target)+'/backlinks',cookie=a),404)
source=update(source,'Source','no links');assert links(source)['items']==[] and backlinks(future)['items']==[]
source=update(source,'Source','[[Future]]');assert backlinks(future)['items'][0]['sourceNoteId']==source['noteId'];expect(call(path(source),'DELETE',cookie=a,headers={'If-Match':'"4"'}),204);assert backlinks(future)['items']==[];expect(call(path(source)+'/links',cookie=a),404)
expect(call('/api/identity/guest-session/revoke','POST',{},a),204);expect(call(path(future)+'/backlinks',cookie=a),401);assert links(private,cookie=b,where=otherbase)['items']==[]
print('PASS retired targets never retarget, unlink/source retirement clears backlinks, and revoked guest cannot read link metadata',flush=True)
