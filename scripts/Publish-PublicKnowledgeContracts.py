"""Publish or verify the immutable Knowledge-owned ExternalMapping v1 event contract."""
import hashlib
import json
import ssl
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

issuer = 'https://brain-keycloak.brain-data.svc:8443/realms/brain-platform'
registry = 'http://brain-registry.brain-events.svc:8080/apis/registry/v3'
tls = ssl.create_default_context(cafile='/trust/tls.crt')


def request(url, method='GET', body=None, credential=None, content_type='application/json'):
    headers = {'Content-Type': content_type}
    if credential:
        headers['Authorization'] = 'Bearer ' + credential
    data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(
            urllib.request.Request(url, data=data, headers=headers, method=method),
            context=tls, timeout=15,
        ) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def token(client, key):
    secret = Path('/clients/' + key).read_text().strip()
    status, data = request(
        issuer + '/protocol/openid-connect/token', 'POST',
        urllib.parse.urlencode(dict(
            grant_type='client_credentials', client_id=client, client_secret=secret,
        )).encode(), content_type='application/x-www-form-urlencoded',
    )
    assert status == 200, 'Token request failed for ' + client + ': status ' + str(status)
    credential = json.loads(data)['access_token']
    print('PASS token issued: ' + client, flush=True)
    return credential


publisher=token('registry-publisher','publisher')
reader=token('registry-reader','reader')
for group,artifact,filename in [('knowledge','public-record-events','knowledge_public_record_v1.proto'),('space','public-capability-events','space_public_capability_v1.proto')]:
 content=Path('/scripts/'+filename).read_text()
 status,_=request(registry+'/groups','POST',dict(groupId=group),publisher);assert status in (200,201,409)
 url=registry+'/groups/'+group+'/artifacts/'+artifact
 status,data=request(url+'/versions/1/content',credential=reader)
 if status==404:
  body=dict(artifactId=artifact,artifactType='PROTOBUF',firstVersion=dict(version='1',content=dict(content=content,contentType='application/x-protobuf')))
  status,_=request(registry+'/groups/'+group+'/artifacts','POST',body,publisher);assert status in (200,201)
 else:assert status==200 and data.decode()==content,'Immutable contract differs'
 for rule,config in [('VALIDITY','FULL'),('COMPATIBILITY','FULL_TRANSITIVE')]:
  status,_=request(url+'/rules','POST',dict(ruleType=rule,config=config),publisher);assert status in (200,201,204,409)
  status,data=request(url+'/rules/'+rule,credential=reader);assert status==200 and json.loads(data)['config']==config
 status,data=request(url+'/versions/1/content',credential=reader);assert status==200 and data.decode()==content
 print('PASS '+group+'/'+artifact+' v1 bytes and FULL/FULL_TRANSITIVE; SHA256='+hashlib.sha256(data).hexdigest(),flush=True)
