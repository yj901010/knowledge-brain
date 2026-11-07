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


reader=token('registry-reader','reader')
for group,artifact,filename in [('collaboration','feedback-retraction-events','collaboration_feedback_retraction_v1.proto'),('collaboration','grade-retraction-events','collaboration_grade_retraction_v1.proto'),('notification','group-review-events','notification_group_review_v1.proto')]:
 content=Path('/scripts/'+filename).read_bytes();url=registry+'/groups/'+group+'/artifacts/'+artifact
 status,data=request(url+'/versions/1/content',token=reader);assert status==200 and data==content,'Immutable contract mismatch'
 for rule,expected in [('VALIDITY','FULL'),('COMPATIBILITY','FULL_TRANSITIVE')]:
  status,data=request(url+'/rules/'+rule,token=reader);assert status==200 and json.loads(data)['config']==expected,'Registry rule mismatch'
 print('PASS unchanged '+group+'/'+artifact+'/1 SHA256='+hashlib.sha256(content).hexdigest()+' FULL/FULL_TRANSITIVE',flush=True)
