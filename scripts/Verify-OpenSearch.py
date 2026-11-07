"""Real mTLS, ACL, scoped search and optional restart checks. Prints no private payloads."""
import errno,json,os,ssl,time,urllib.request,urllib.error,uuid,socket
from pathlib import Path
origin='https://brain-opensearch.brain-data.svc:9200'
def context(role):
 path=Path('/search-'+role);value=ssl.create_default_context(cafile=str(path/'ca.crt'));value.load_cert_chain(str(path/'tls.crt'),str(path/'tls.key'));return value
def call(role,method,path,body=None):
 request=urllib.request.Request(origin+path,data=json.dumps(body).encode() if body is not None else None,method=method,headers={'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(request,context=context(role),timeout=5) as response:return response.status,json.load(response)
 except urllib.error.HTTPError as error:
  with error:return error.code,json.load(error)
def id():
 value=uuid.uuid4();return str(uuid.UUID(int=(value.int & ~(15<<76)) | (7<<76)))
if os.getenv('NETWORK_DENIED')=='true':
 try:call('reader','GET','/')
 except (TimeoutError,socket.timeout,urllib.error.URLError) as error:
  cause=error.reason if isinstance(error,urllib.error.URLError) else error
  if not isinstance(cause,(TimeoutError,socket.timeout)) and not (isinstance(cause,OSError) and cause.errno in [errno.EHOSTUNREACH,errno.ENETUNREACH,errno.EACCES,errno.ETIMEDOUT,errno.ECONNREFUSED]):raise RuntimeError('Expected network denial: '+type(cause).__name__+' errno='+str(getattr(cause,'errno',None))) from None
  print('PASS non-client Pod connection rejected/timed out despite valid reader certificate; compare allowed control Job',flush=True);raise SystemExit(0)
 raise RuntimeError('Non-client Pod reached OpenSearch')
space,note=id(),id();source={'spaceId':space,'noteId':note,'visibility':'PRIVATE','state':'ACTIVE','title':'RestartFixture','markdown':'test body'}
status,_=call('projector','PUT','/brain-notes-v1/_doc/'+note+'?version=1&version_type=external',source);assert status==201,'Fixture write failed'
for role,method,path,body in [('reader','PUT','/brain-notes-v1/_doc/'+id(),source),('reader','GET','/brain-notes-v1/_doc/'+note,None),('reader','GET','/_plugins/_security/api/roles',None),('projector','DELETE','/brain-notes-v1/_doc/'+note,None),('projector','PUT','/forbidden-other-index',{}),('projector','POST','/brain-notes-v1/_search',{'query':{'match_all':{}}})]:
 assert call(role,method,path,body)[0]==403,'Least privilege ACL failed'
print('PASS reader/projector cannot delete, administer, create other indices or cross roles',flush=True)
try:
 value=ssl.create_default_context(cafile='/search-reader/ca.crt')
 with urllib.request.urlopen(origin+'/',context=value,timeout=5):raise RuntimeError('Client certificate was not required')
except (ssl.SSLError,urllib.error.URLError) as error:
 if isinstance(error,urllib.error.URLError) and not isinstance(error.reason,ssl.SSLError):raise
print('PASS trusted HTTPS still requires a client certificate',flush=True)
if os.getenv('RESTART_CHECK')=='true':
 print('RESTART_READY',flush=True)
 for i in range(120):
  if Path('/state/resume').exists():break
  time.sleep(1)
 else:raise RuntimeError('Restart operator did not resume validation')
status,stored=call('projector','GET','/brain-notes-v1/_doc/'+note);assert status==200 and stored['_source']==source and stored['_version']==1,'Persistent index lost fixture'
assert call('projector','PUT','/brain-notes-v1/_doc/'+note+'?version=1&version_type=external',source)[0]==409,'External version lost after restart'
query={'query':{'bool':{'filter':[{'term':{'spaceId':space}},{'term':{'visibility':'PRIVATE'}},{'term':{'state':'ACTIVE'}}],'must':[{'match':{'title':'RestartFixture'}}]}},'track_total_hits':True,'_source':['noteId','title']}
for i in range(30):
 status,result=call('reader','POST','/brain-notes-v1/_search',query)
 if status==200 and result['hits']['total']['value']==1:break
 time.sleep(.2)
else:raise RuntimeError('Search fixture was not found')
print('PASS persisted document, external version and scoped search'+(' after actual Pod replacement' if os.getenv('RESTART_CHECK')=='true' else ''),flush=True)
