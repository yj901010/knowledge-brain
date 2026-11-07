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


import hashlib,http.client,urllib.parse,uuid

def object_request(url,method='GET',body=None,headers=None):
 parsed=urllib.parse.urlsplit(url)
 assert parsed.scheme=='https' and parsed.netloc=='localhost:9443' and parsed.path.startswith('/brain-media/') or parsed.scheme=='https' and parsed.netloc=='localhost:9443' and parsed.path=='/brain-media','Unexpected configured storage origin'
 connection=http.client.HTTPSConnection('brain-minio.brain-data.svc',9000,context=context,timeout=15)
 h={'Host':'localhost:9443'};h.update(headers or {})
 try:
  connection.request(method,parsed.path+('?' + parsed.query if parsed.query else ''),body=body,headers=h)
  response=connection.getresponse();return response.status,dict(response.headers),response.read()
 finally:connection.close()

def upload(form,data,override=None):
 boundary='brain-'+uuid.uuid4().hex;fields=dict(form['fields']);fields.update(override or {});parts=[]
 for key,value in fields.items():parts.append(('--'+boundary+'\r\nContent-Disposition: form-data; name="'+key+'"\r\n\r\n'+value+'\r\n').encode())
 parts += [('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="upload"\r\nContent-Type: application/octet-stream\r\n\r\n').encode(),data,('\r\n--'+boundary+'--\r\n').encode()]
 return object_request(form['url'],'POST',b''.join(parts),{'Content-Type':'multipart/form-data; boundary='+boundary})

def file_request(collection,cookie,data=b'%PDF-KnowledgeBrain attachment',sha=None):
 body={'filename':'attachment.pdf','mimeType':'application/pdf','byteSize':len(data),'sha256':sha or hashlib.sha256(data).hexdigest()}
 return expect(call(collection,'POST',body,cookie),201)

a,space=browser();b,other=browser();base='/api/spaces/'+space;collection=base+'/media';notes=base+'/notes'
expect(call(collection),401);expect(call(collection,cookie=b),404);expect(call(collection+'?url=https://evil.example',cookie=a),400)
expect(call(collection,'POST',{'filename':'../a','mimeType':'application/pdf','byteSize':1,'sha256':'a'*64},a),400)
expect(call(collection,'POST',{'filename':'a','mimeType':'text/html','byteSize':1,'sha256':'a'*64},a),400)
expect(call(collection,'POST',{'filename':'a','mimeType':'application/pdf','byteSize':1,'sha256':'a'*64},a,{'Origin':'https://evil.example'}),403)
note=expect(call(notes,'POST',{'title':'Attachment note','markdown':'# body'},a),201);path=notes+'/'+note['noteId'];attachments=path+'/attachments'
empty=call(attachments,cookie=a);assert expect(empty,200)['attachmentVersion']==0 and empty[1]['ETag']=='"a0"'
data=b'%PDF-KnowledgeBrain attachment';pending=file_request(collection,a,data);media=pending['media'];mpath=collection+'/'+media['mediaId'];form=pending['upload'];assert media['state']=='PENDING' and media['version']==1
expect(call(attachments,'PUT',{'mediaIds':[media['mediaId']]},a,{'If-Match':'"a0"'}),409);assert expect(call(attachments,cookie=a),200)['items']==[]
expect(call(mpath+'/download','POST',{},a),409);expect(call(mpath+'/complete','POST',{},a),409)
assert upload(form,data,{'key':'uploads/sealed/forbidden'})[0]==403
assert upload(form,data+b'x')[0]>=400
assert 200<=upload(form,bytes(len(data)))[0]<300;expect(call(mpath+'/complete','POST',{},a),400)
form=expect(call(mpath+'/upload-form','POST',{},a),200);assert 200<=upload(form,data)[0]<300
available=expect(call(mpath+'/complete','POST',{},a),200);assert available['state']=='AVAILABLE' and available['version']==2 and available['sha256']==hashlib.sha256(data).hexdigest();assert expect(call(mpath+'/complete','POST',{},a),200)==available
expect(call(mpath+'/upload-form','POST',{},a),409);expect(call(mpath+'/complete','POST',{'url':'https://evil.example'},a),400)
for endpoint in (mpath,mpath+'/complete',mpath+'/download'):
 expect(call(endpoint,'GET' if endpoint==mpath else 'POST',None if endpoint==mpath else {},b),404)
foreign=file_request('/api/spaces/'+other+'/media',b);expect(call(attachments,'PUT',{'mediaIds':[foreign['media']['mediaId']]},a,{'If-Match':'"a0"'}),404)
expect(call(attachments,'PUT',{'mediaIds':[]},a),428);expect(call(attachments,'PUT',{'mediaIds':[]},a,{'If-Match':'W/"a0"'}),400);expect(call(attachments,'PUT',{'mediaIds':[media['mediaId'],media['mediaId']]},a,{'If-Match':'"a0"'}),400)
first=expect(call(attachments,'PUT',{'mediaIds':[media['mediaId']]},a,{'If-Match':'"a0"'}),200);assert first['attachmentVersion']==1 and first['items'][0]['mediaId']==media['mediaId']
expect(call(attachments,'PUT',{'mediaIds':[]},a,{'If-Match':'"a0"'}),412);expect(call(attachments,cookie=b),404);expect(call(attachments),401)
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:results=list(workers.map(lambda _:call(attachments,'PUT',{'mediaIds':[media['mediaId']]},a,{'If-Match':'"a1"'}),range(2)))
assert sorted(x[0] for x in results)==[200,412];current=expect(next(x for x in results if x[0]==200),200);assert current['items'][0]['attachmentId']==first['items'][0]['attachmentId'];assert expect(call(path,cookie=a),200)['version']==1
expect(call(path,'PUT',{'title':'Edited body','markdown':'retained attachment'},a,{'If-Match':'"1"'}),200);assert expect(call(attachments,cookie=a),200)==current
metadata=expect(call(path+'/metadata','PUT',{'tags':['files'],'properties':{}},a,{'If-Match':'"m0"'}),200);assert expect(call(attachments,cookie=a),200)==current
# Same Media can belong to two Content-owned relations; Daily is an ordinary Note.
daily=expect(call(base+'/daily-notes','POST',{'date':'2026-10-07','timezone':'Asia/Seoul'},a),201);dailyPath=notes+'/'+daily['note']['noteId'];expect(call(dailyPath+'/attachments','PUT',{'mediaIds':[media['mediaId']]},a,{'If-Match':'"a0"'}),200)
download=expect(call(mpath+'/download','POST',{},a),200);result=object_request(download['url']);assert result[0]==200 and result[2]==data and result[1]['Content-Type']=='application/octet-stream' and result[1]['Content-Disposition'].startswith('attachment;')
# Replaying a still-valid upload form changes staging only; published bytes stay sealed.
assert 200<=upload(form,bytes(len(data)))[0]<300;assert object_request(download['url'])[2]==data
public=urllib.parse.urlsplit(download['url']);assert object_request('https://localhost:9443'+public.path)[0]==403
history=expect(call(attachments+'/revisions',cookie=a),200);assert len(history)==2
print('PASS private Media, exact signed upload bounds/key, SHA-256 completion, immutable replay-safe download, cross-Space/Origin denial, independent attachment ETags, concurrent winner, shared Daily attachment and history',flush=True)
if os.getenv('BRAIN_TEST_RESTART')=='true':
 print('PHASE1_WAITING_FOR_RESTART',flush=True);end=time.monotonic()+480
 while time.monotonic()<end and not Path('/tmp/restart.complete').exists():time.sleep(1)
 assert Path('/tmp/restart.complete').exists(),'Restart marker timeout'
 assert expect(call(mpath,cookie=a),200)==available;assert expect(call(attachments,cookie=a),200)==current;assert expect(call(attachments+'/revisions',cookie=a),200)==history;assert expect(call(path+'/metadata',cookie=a),200)==metadata
 download=expect(call(mpath+'/download','POST',{},a),200);assert object_request(download['url'])[2]==data
 print('PASS replacement of Media, Content and MinIO Pods retains exact metadata, relation IDs, versions, history and SHA-256 verified blob from persistent storage',flush=True)
expect(call(path,'DELETE',cookie=a,headers={'If-Match':'"2"'}),204);expect(call(attachments,cookie=a),404);retired=expect(call(attachments+'/revisions',cookie=a),200);assert retired[0]['snapshot']['items']==[] and len(retired)==3
assert expect(call(dailyPath+'/attachments',cookie=a),200)['items'][0]['mediaId']==media['mediaId'];download=expect(call(mpath+'/download','POST',{},a),200);assert object_request(download['url'])[2]==data
expect(call('/api/identity/guest-session/revoke','POST',{},a),204);expect(call(mpath+'/download','POST',{},a),401);expect(call(collection,cookie=a),401);expect(call(dailyPath+'/attachments',cookie=a),401)
print('PASS retiring one Note preserves shared Media and old relation history; guest revocation denies new Media capabilities and attachment reads',flush=True)
