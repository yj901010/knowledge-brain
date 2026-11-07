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

publisher=token('registry-publisher','publisher'); reader=token('registry-reader','reader')
for group,artifact,filename in [('collaboration','submission-feedback-events','collaboration_submission_feedback_v1.proto')]:
 content=Path('/scripts/'+filename).read_text()
 status,_=request(registry+'/groups','POST',dict(groupId=group),publisher);assert status in (200,201,409)
 url=registry+'/groups/'+group+'/artifacts/'+artifact
 status,data=request(url+'/versions/1/content',token=reader)
 if status==404:
  status,_=request(registry+'/groups/'+group+'/artifacts','POST',dict(artifactId=artifact,artifactType='PROTOBUF',firstVersion=dict(version='1',content=dict(content=content,contentType='application/x-protobuf'))),publisher);assert status in (200,201)
 else:assert status==200 and data.decode()==content,'Immutable Group contract differs'
 for rule,config in [('VALIDITY','FULL'),('COMPATIBILITY','FULL_TRANSITIVE')]:
  status,_=request(url+'/rules','POST',dict(ruleType=rule,config=config),publisher);assert status in (200,201,204,409)
  status,data=request(url+'/rules/'+rule,token=reader);assert status==200 and json.loads(data)['config']==config
 status,data=request(url+'/versions/1/content',token=reader);assert status==200 and data.decode()==content
 print('PASS '+group+'/'+artifact+'/1 immutable SHA256='+hashlib.sha256(data).hexdigest(),flush=True)
