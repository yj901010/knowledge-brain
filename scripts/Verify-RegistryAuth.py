import json,ssl,urllib.request,urllib.parse,urllib.error,os,time,hashlib
from pathlib import Path
issuer='https://brain-keycloak.brain-data.svc:8443/realms/brain-platform'
registry='http://brain-registry.brain-events.svc:8080/apis/registry/v3'
tls=ssl.create_default_context(cafile='/trust/tls.crt')
content='syntax = "proto3"; package platform.validation; message Probe { string value = 1; }'
def request(url,method='GET',body=None,token=None,ctype='application/json'):
 headers={'Content-Type':ctype}
 if token: headers['Authorization']='Bearer '+token
 data=body if isinstance(body,bytes) else json.dumps(body).encode() if body is not None else None
 try:
  with urllib.request.urlopen(urllib.request.Request(url,data=data,headers=headers,method=method),context=tls,timeout=15) as response:
   return response.status,response.read()
 except urllib.error.HTTPError as error: return error.code,error.read()
def token(client,key):
 secret=Path('/clients/'+key).read_text().strip()
 status,data=request(issuer+'/protocol/openid-connect/token','POST',urllib.parse.urlencode(dict(grant_type='client_credentials',client_id=client,client_secret=secret)).encode(),ctype='application/x-www-form-urlencoded')
 assert status==200,'Token request failed for '+client+' status '+str(status)
 result=json.loads(data)['access_token']
 print('PASS token issued: '+client,flush=True)
 return result
publisher=token('registry-publisher','publisher')
reader=token('registry-reader','reader')
unprivileged=token('registry-unprivileged','unprivileged')
if os.getenv('VERIFY_ARTIFACT'):
 artifact=os.environ['VERIFY_ARTIFACT']
 status,data=request(registry+'/groups/default/artifacts/'+artifact+'/versions/1/content',token=reader)
 assert status==200 and data.decode()==content,'Persistence read mismatch: '+str(status)
 print('PASS restart persistence content SHA256='+hashlib.sha256(data).hexdigest(),flush=True)
else:
 for label,auth in [('anonymous',None),('unprivileged',unprivileged)]:
  status,_=request(registry+'/groups',token=auth)
  assert status in [401,403],label+' unexpectedly allowed: '+str(status)
  print('PASS '+label+' read rejected '+str(status),flush=True)
 artifact='platform-auth-smoke-'+str(int(time.time()*1000))
 body=dict(artifactId=artifact,artifactType='PROTOBUF',firstVersion=dict(version='1',content=dict(content=content,contentType='application/x-protobuf')))
 status,_=request(registry+'/groups/default/artifacts','POST',body,reader)
 assert status==403,'readonly write was not denied: '+str(status)
 print('PASS readonly write rejected 403',flush=True)
 status,_=request(registry+'/groups/default/artifacts','POST',body,publisher)
 assert status in [200,201],'publisher create failed: '+str(status)
 print('PASS publisher Protobuf registration '+str(status),flush=True)
 status,data=request(registry+'/groups/default/artifacts/'+artifact+'/versions/1/content',token=reader)
 assert status==200 and data.decode()==content,'readonly content read failed: '+str(status)
 print('PASS readonly content read SHA256='+hashlib.sha256(data).hexdigest(),flush=True)
 print('ARTIFACT_ID='+artifact,flush=True)
