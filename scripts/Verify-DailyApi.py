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

from datetime import datetime,timezone,timedelta

a,space=browser();b,other=browser();base='/api/spaces/'+space;daily=base+'/daily-notes';notes=base+'/notes'
for zone,offset in [('UTC',0),('Asia%2FSeoul',9)]:
 before=datetime.now(timezone(timedelta(hours=offset))).date().isoformat();today=expect(call(daily+'/today?timezone='+zone,cookie=a),200);after=datetime.now(timezone(timedelta(hours=offset))).date().isoformat();assert today['date'] in (before,after) and today['entry'] is None
assert expect(call(notes,cookie=a),200)['items']==[];assert expect(call(daily,cookie=a),200)['items']==[]
expect(call(daily+'/today',cookie=a),400);expect(call(daily+'/today?timezone=Unknown%2FZone',cookie=a),400);expect(call(daily+'/today?timezone=UTC&timezone=UTC',cookie=a),400);expect(call(daily+'?url=https://evil.example',cookie=a),400);expect(call(daily+'?from=2026-10-10&to=2026-10-01',cookie=a),400)
for body in ({'date':'2026-02-29','timezone':'Asia/Seoul'},{'date':'2026-10-07','timezone':'+09:00'},{'date':'2026-10-07','timezone':'UTC','markdown':'lost'}):
 expect(call(daily,'POST',body,a),400)
expect(call(daily,'POST',{'date':'2026-10-07','timezone':'Asia/Seoul'},a,{'Origin':'https://evil.example'}),403)
expect(call(daily,cookie=b),404);expect(call(daily),401);expect(call(daily+'/2026-10-07',cookie=b),404);expect(call(daily+'/today?timezone=UTC',cookie=b),404)
body={'date':'2026-10-07','timezone':'Asia/Seoul'}
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:results=list(workers.map(lambda _:call(daily,'POST',body,a),range(2)))
assert sorted(x[0] for x in results)==[200,201];entry=expect(next(x for x in results if x[0]==201),201);note=entry['note'];path=notes+'/'+note['noteId'];assert note['version']==1 and entry['date']=='2026-10-07' and entry['timezone']=='Asia/Seoul'
assert len(expect(call(notes,cookie=a),200)['items'])==1;assert len(expect(call(path+'/revisions',cookie=a),200))==1
for date in ('2026-10-05','2026-10-09'):expect(call(daily,'POST',{'date':date,'timezone':'Asia/Seoul'},a),201)
page=expect(call(daily+'?limit=1',cookie=a),200);assert page['items'][0]['date']=='2026-10-09' and page['nextBefore']=='2026-10-09';assert expect(call(daily+'?limit=1&before='+page['nextBefore'],cookie=a),200)['items'][0]['date']=='2026-10-07'
assert [x['date'] for x in expect(call(daily+'?from=2026-10-05&to=2026-10-07',cookie=a),200)['items']]==['2026-10-07','2026-10-05']
expect(call(daily,'POST',{'date':'2026-10-07','timezone':'UTC'},a),409)
foreign=expect(call('/api/spaces/'+other+'/daily-notes','POST',body,b),201);assert foreign['note']['noteId']!=note['noteId']
source=expect(call(notes,'POST',{'title':'Source','markdown':'[[2026-10-07]]'},a),201);sourcepath=notes+'/'+source['noteId'];link=expect(call(sourcepath+'/links',cookie=a),200)['items'][0];assert link['targetNoteId']==note['noteId'] and link['status']=='RESOLVED'
expect(call(path,'PUT',{'title':'Journal renamed','markdown':'# 기록\n[[Source]]'},a,{'If-Match':'"1"'}),200)
metadata=expect(call(path+'/metadata','PUT',{'tags':['journal'],'properties':{'done':{'type':'BOOLEAN','value':True}}},a,{'If-Match':'"m0"'}),200)
reopened=expect(call(daily,'POST',body,a),200);assert reopened['note']['version']==2 and reopened['note']['title']=='Journal renamed' and reopened['note']['markdown']=='# 기록\n[[Source]]';assert expect(call(path+'/metadata',cookie=a),200)==metadata
assert expect(call(sourcepath+'/links',cookie=a),200)['items'][0]['linkId']==link['linkId'];assert expect(call(daily+'/2026-10-07',cookie=a),200)==reopened
expect(call(daily+'/2026-10-07','PUT',body,a),403);expect(call(daily+'/2026-10-07','DELETE',cookie=a),403)
history=expect(call(path+'/revisions?limit=10',cookie=a),200);snapshot=expect(call(daily,cookie=a),200)
print('PASS explicit timezone today without mutation, date validation, Origin/privacy, one concurrent creation, no-overwrite reopen, immutable timezone, date pagination and normal Note links/tags/properties',flush=True)
if os.getenv('BRAIN_TEST_RESTART')=='true':
 print('PHASE1_WAITING_FOR_RESTART',flush=True);end=time.monotonic()+240
 while time.monotonic()<end and not Path('/tmp/restart.complete').exists():time.sleep(1)
 assert Path('/tmp/restart.complete').exists(),'Restart marker timeout'
 assert expect(call(daily+'/2026-10-07',cookie=a),200)==reopened;assert expect(call(daily,cookie=a),200)==snapshot;assert expect(call(path+'/metadata',cookie=a),200)==metadata;assert expect(call(path+'/revisions?limit=10',cookie=a),200)==history
 print('PASS Content Pod replacement retains exact Daily date/timezone, Note ID, Markdown, metadata, Journal page and revision history',flush=True)
expect(call(path,'DELETE',cookie=a,headers={'If-Match':'"2"'}),204);expect(call(daily+'/2026-10-07',cookie=a),404);expect(call(daily,'POST',body,a),409);assert len(expect(call(path+'/revisions',cookie=a),200))==3;assert len(expect(call(daily,cookie=a),200)['items'])==2
expect(call('/api/identity/guest-session/revoke','POST',{},a),204);expect(call(daily,cookie=a),401);expect(call(daily+'/today?timezone=UTC',cookie=a),401);expect(call('/api/spaces/'+other+'/daily-notes/2026-10-07',cookie=b),200)
print('PASS retirement hides Daily entry and Journal list, prevents accidental recreation, keeps private revision history, and guest revocation denies all Daily reads',flush=True)
