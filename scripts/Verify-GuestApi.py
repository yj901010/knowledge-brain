"""Real HTTPS browser/session checks. Credentials stay in process memory, never logs."""
import http.cookies,json,os,pathlib,secrets,ssl,time,urllib.error,urllib.request
identity='https://identity-service.brain-apps.svc:8080'
space='https://space-service.brain-apps.svc:8080'
via_gateway=os.environ.get('BRAIN_TEST_VIA_GATEWAY')=='true'
if via_gateway:identity=space='https://gateway-service.brain-apps.svc:8080'
context=ssl.create_default_context(cafile='/api-trust/ca.crt')
origin='https://localhost:8443'
def call(base,path,method='GET',cookie=None,headers=None,body=None):
 values={'Accept':'application/json'}
 if cookie:values['Cookie']=cookie
 if headers:values.update(headers)
 request=urllib.request.Request(base+path,data=body,headers=values,method=method)
 try:
  with urllib.request.urlopen(request,context=context,timeout=10) as response:return response.status,response.headers,response.read()
 except urllib.error.HTTPError as response:return response.code,response.headers,response.read()
def check(condition,message):
 if not condition:raise RuntimeError(message)
if os.environ.get('BRAIN_TEST_NETWORK_DENY')=='true':
 check(call(space,'/actuator/health/readiness')[0]==200,'Denial verifier cannot reach control Space endpoint')
 for attempt in range(2):
  try:call(identity,'/actuator/health/readiness')
  except urllib.error.URLError as error:
   if isinstance(error.reason,ssl.SSLCertVerificationError):raise
  else:raise RuntimeError('Unlabeled Pod reached Identity despite NetworkPolicy')
  time.sleep(1)
 print('PASS Identity NetworkPolicy: unlabeled validation Pod cannot connect to Identity; Space control endpoint remains reachable',flush=True)
 raise SystemExit(0)
for base in (identity,space):
 deadline=time.monotonic()+45
 while True:
  try:
   if call(base,'/actuator/health/readiness')[0]==200:break
  except urllib.error.URLError as error:
   if isinstance(error.reason,ssl.SSLCertVerificationError):raise
  check(time.monotonic()<deadline,'Application HTTPS readiness unavailable from verification Pod');time.sleep(1)
def create():
 status,headers,body=call(identity,'/api/identity/guest-session','POST',headers={'Origin':origin,'Sec-Fetch-Site':'same-origin','Content-Type':'application/json'},body=b'{}')
 check(status==201,'Guest creation failed')
 cookie=headers.get('Set-Cookie','')
 for expected in ('__Host-brain_guest=','Path=/','Secure','HttpOnly','SameSite=Lax','Max-Age=15552000'):check(expected in cookie,'Missing secure cookie attribute')
 check('Domain=' not in cookie,'Cookie must be host-only')
 check('no-store' in headers.get('Cache-Control',''),'Session response is cacheable')
 check('JSESSIONID' not in cookie,'Unexpected servlet session')
 parsed=http.cookies.SimpleCookie();parsed.load(cookie);token=parsed['__Host-brain_guest'].value
 data=json.loads(body);check(token not in body.decode(),'Credential leaked into JSON')
 check(data['kind']=='GUEST','Wrong application principal kind')
 return '__Host-brain_guest='+token,data
def personal(cookie):
 deadline=time.monotonic()+60
 while time.monotonic()<deadline:
  status,headers,body=call(space,'/api/spaces/personal',cookie=cookie)
  if status==202:
   check(headers.get('Retry-After')=='1','Pending provision lacks Retry-After');time.sleep(.5);continue
  check(status==200,'Personal Space lookup failed')
  data=json.loads(body);check(data['kind']=='PERSONAL' and data['visibility']=='PRIVATE' and data['role']=='OWNER','Invalid Personal Space permission')
  check('no-store' in headers.get('Cache-Control',''),'Space response is cacheable')
  return data
 raise RuntimeError('Personal Space provisioning timed out')
for invalidOrigin in (None,'https://evil.example','null','https://localhost:8443.evil.example'):
 headers={'Content-Type':'application/json'}
 if invalidOrigin:headers['Origin']=invalidOrigin
 check(call(identity,'/api/identity/guest-session','POST',headers=headers,body=b'{}')[0]==403,'Cross-origin creation accepted')
check(call(identity,'/api/identity/guest-session','POST',headers={'Origin':origin,'Sec-Fetch-Site':'cross-site','Content-Type':'application/json'},body=b'{}')[0]==403,'Cross-site metadata accepted')
first,principal=create();second,other=create()
check(principal['principalId']!=other['principalId'],'Different browsers share principal')
status,headers,body=call(identity,'/api/identity/guest-session','POST',cookie=first,headers={'Origin':origin,'Content-Type':'application/json'},body=b'{}')
check(status==200 and json.loads(body)==principal and headers.get('Set-Cookie') is None,'Returning browser identity changed')
owned=personal(first);otherSpace=personal(second)
check(owned['spaceId']!=otherSpace['spaceId'],'Different browsers share Space')
path='/api/spaces/'+owned['spaceId']
check(call(space,path,cookie=first)[0]==200,'Owner denied')
check(call(space,path,cookie=second,headers={'X-Principal-Id':principal['principalId'],'X-User-Id':principal['principalId']})[0]==404,'Spoofed principal or another browser accessed Space')
check(call(space,path,headers={'X-Principal-Id':principal['principalId']})[0]==401,'Header-only authentication accepted')
check(call(space,'/api/spaces/not-a-uuid',cookie=first)[0]==(403 if via_gateway else 400),'Invalid Space identifier not rejected')
check(call(identity,'/internal/identity/guest-introspection','POST',headers={'Content-Type':'application/json'},body=b'{broken')[0]==(403 if via_gateway else 400),'Malformed or internal request not rejected')
check(call(identity,'/api/identity/guest-session')[0]==401,'Missing credential accepted')
for invalid in ('__Host-brain_guest=attacker','__Host-brain_guest='+secrets.token_urlsafe(32),first+'; '+first):
 check(call(identity,'/api/identity/guest-session',cookie=invalid)[0]==401,'Invalid or duplicate cookie accepted by Identity')
 check(call(space,path,cookie=invalid)[0]==401,'Invalid or duplicate cookie accepted by Space')
for authorization in (None,'Bearer '+secrets.token_urlsafe(32)):
 headers={'Content-Type':'application/json'}
 if authorization:headers['Authorization']=authorization
 check(call(identity,'/internal/identity/guest-introspection','POST',cookie=first,headers=headers,body=json.dumps({'token':first.split('=',1)[1]}).encode())[0]==(403 if via_gateway else 401),'Browser bypassed internal service authentication')
if via_gateway:
 for blocked_path in ('/internal/identity/guest-introspection','/api/identity/guest-session/extra','/api/spaces/personal/extra','/actuator/env'):
  check(call(identity,blocked_path,cookie=first)[0]==403,'Gateway forwarded a forbidden route')
 check(call(identity,'/api/identity/guest-session','PUT',cookie=first,body=b'{}')[0]==403,'Gateway accepted an undeclared method')
 check(call(identity,'/api/identity/guest-session','POST',headers={'Origin':origin,'Content-Type':'application/json'},body=b'x'*1025)[0]==413,'Gateway body limit not enforced')
 check(call(identity,'/api/spaces/personal?url=https://evil.example',cookie=first)[0]==400,'Gateway accepted query routing input')
 print('PASS Gateway common HTTPS origin: fixed routes, unsafe methods/internal paths denied, request size bound and query rejection',flush=True)
for base in (identity,space):
 check(call(base,'/actuator/env')[0]==403,'Management data exposed')
 check(call(base,'/actuator/health/readiness')[0]==200,'Application not ready')
print('PASS HTTPS guest API phase 1: secure host-only cookie, browser restore, async PRIVATE OWNER Space, other-principal/header spoof denial, strict Origin, malformed/duplicate credentials and internal service authorization',flush=True)
if os.environ.get('BRAIN_TEST_WAIT_RESTART')=='true':
 deadline=time.monotonic()+300
 outage_checked=False;space_outage_checked=False
 while not pathlib.Path('/tmp/restart.complete').exists() and time.monotonic()<deadline:
  if pathlib.Path('/tmp/identity.down').exists() and not outage_checked:
   status=call(space,path,cookie=first)[0]
   check(status in ((502,503,504) if via_gateway else (503,)),'Identity outage did not fail closed; status='+str(status))
   print('PASS HTTPS Identity outage: authentication fails closed; status='+str(status)+', no cached principal bypass',flush=True);outage_checked=True
   if via_gateway:
    status=call(identity,'/api/identity/guest-session',cookie=first)[0]
    check(status in (502,504),'Gateway Identity upstream outage did not fail closed; status='+str(status))
    print('PASS Gateway Identity upstream outage: status='+str(status),flush=True)
  if via_gateway and pathlib.Path('/tmp/space.down').exists() and not space_outage_checked:
   status=call(space,'/api/spaces/personal',cookie=first)[0]
   check(status in (502,504),'Gateway Space upstream outage did not fail closed; status='+str(status))
   print('PASS Gateway Space outage: upstream unavailable; status='+str(status),flush=True);space_outage_checked=True
  time.sleep(1)
 check(pathlib.Path('/tmp/restart.complete').exists(),'Restart signal timed out')
 check(json.loads(call(identity,'/api/identity/guest-session',cookie=first)[2])==principal,'Identity lost across restart')
 check(personal(first)==owned and personal(second)==otherSpace,'Space lost or recreated across restart')
 print('PASS HTTPS guest API phase 2: same browser principal and PRIVATE Space restored after Identity and Space Pod replacement',flush=True)
status,headers,body=call(identity,'/api/identity/guest-session/revoke','POST',cookie=first,headers={'Origin':origin,'Content-Type':'application/json'},body=b'{}')
check(status==204 and 'Max-Age=0' in headers.get('Set-Cookie',''),'Logout did not expire cookie')
check(call(identity,'/api/identity/guest-session',cookie=first)[0]==401,'Revoked credential still resolves')
check(call(space,path,cookie=first)[0]==401,'Space accepted revoked credential')
check(call(identity,'/api/identity/guest-session',cookie=second)[0]==200 and personal(second)==otherSpace,'Logout affected another browser')
print('PASS HTTPS guest API phase 3: immediate cross-service revocation, cookie expiry, independent browser unaffected; no credentials logged',flush=True)
