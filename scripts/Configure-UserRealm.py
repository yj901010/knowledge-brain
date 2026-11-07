"""Run only inside the scoped local Keycloak bootstrap Job; never print secrets."""
import json,ssl,urllib.request,urllib.parse,urllib.error
from pathlib import Path
base='https://brain-keycloak.brain-data.svc:8443'
tls=ssl.create_default_context(cafile='/trust/tls.crt')
def request(path,method='GET',body=None,token=None,form=False):
 headers={'Content-Type':'application/x-www-form-urlencoded' if form else 'application/json'}
 if token:headers['Authorization']='Bearer '+token
 data=urllib.parse.urlencode(body).encode() if form else json.dumps(body).encode() if body is not None else None
 try:
  with urllib.request.urlopen(urllib.request.Request(base+path,data=data,method=method,headers=headers),context=tls,timeout=15) as r:return r.status,r.read()
 except urllib.error.HTTPError as e:return e.code,e.read()
status,data=request('/realms/master/protocol/openid-connect/token','POST',dict(grant_type='password',client_id='admin-cli',username='bootstrap-admin',password=Path('/admin/password').read_text().strip()),form=True)
assert status==200,'Local bootstrap authentication failed'
token=json.loads(data)['access_token']
text=Path('/scripts/realm.json').read_text()
for key,value in [('USER_FRONTEND_URL','https://localhost:8445'),('USER_CALLBACK_URL','https://localhost:8443/api/identity/login/callback'),('USER_CLIENT_SECRET',Path('/login/client-secret').read_text().strip())]:text=text.replace('${'+key+'}',value)
realm=json.loads(text);client=realm.pop('clients')[0]
status,_=request('/admin/realms/brain-users',token=token)
if status==404:
 status,_=request('/admin/realms','POST',realm,token);assert status==201,'User Realm create failed'
else:
 assert status==200,'User Realm lookup failed'
 status,_=request('/admin/realms/brain-users','PUT',realm,token);assert status==204,'User Realm update failed'
status,data=request('/admin/realms/brain-users/clients?clientId=brain-web',token=token);assert status==200
clients=json.loads(data)
if clients:
 assert len(clients)==1
 status,_=request('/admin/realms/brain-users/clients/'+clients[0]['id'],'PUT',client,token);assert status==204
else:
 status,_=request('/admin/realms/brain-users/clients','POST',client,token);assert status==201
status,data=request('/realms/brain-users/.well-known/openid-configuration');assert status==200
discovery=json.loads(data);assert discovery['issuer']=='https://localhost:8445/realms/brain-users','Public user issuer mismatch'
assert discovery['authorization_endpoint'].startswith('https://localhost:8445/')
status,data=request('/realms/brain-platform/.well-known/openid-configuration');assert status==200
assert json.loads(data)['issuer']==base+'/realms/brain-platform','Registry issuer changed'
print('PASS user Realm public issuer, PKCE confidential client and unchanged Registry issuer',flush=True)
