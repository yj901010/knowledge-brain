"""No credential or resource contents are printed by this verifier."""
import json,ssl,urllib.request,urllib.error,urllib.parse,re
from pathlib import Path
origin='https://brain-headlamp.brain-ops.svc:80';context=ssl.create_default_context(cafile='/trust/ca.crt');token=Path('/var/run/secrets/kubernetes.io/serviceaccount/token').read_text().strip()
def call(path,method='GET',body=None,authenticated=True):
 headers={'Content-Type':'application/json'}
 if authenticated:headers['Authorization']='Bearer '+token
 payload=json.dumps(body).encode() if body is not None else None
 try:
  with urllib.request.urlopen(urllib.request.Request(origin+path,data=payload,headers=headers,method=method),context=context,timeout=15) as response:return response.status,response.read(),dict(response.headers)
 except urllib.error.HTTPError as error:return error.code,error.read(),dict(error.headers)
def expect(path,status=200):
 result=call(path);assert result[0]==status,path+' expected '+str(status)+' got '+str(result[0]);return json.loads(result[1]) if status==200 else None
status,body,_=call('/',authenticated=False);assert status==200 and b'headlamp' in body.lower();print('PASS HTTPS Headlamp HTML served with the local CA',flush=True)
for asset in re.findall(r'<script[^>]+src="([^"]+)"',body.decode()):
 parsed=urllib.parse.urlsplit(urllib.parse.urljoin(origin+'/',asset));assert parsed.netloc==urllib.parse.urlsplit(origin).netloc
 status,script,_=call(parsed.path,authenticated=False);assert status==200 and script
print('PASS frontend JavaScript assets served from the same trusted HTTPS origin',flush=True)

base='/clusters/knowledge-brain'
nodes=expect(base+'/api/v1/nodes');assert len(nodes['items'])==2
pods=expect(base+'/api/v1/pods');assert pods['items'];deployments=expect(base+'/apis/apps/v1/namespaces/brain-apps/deployments');assert {'identity-service','space-service','content-service','media-service','gateway-service'}<=set(x['metadata']['name'] for x in deployments['items'])
metrics=expect(base+'/apis/metrics.k8s.io/v1beta1/nodes');assert len(metrics['items'])==2
pod=next(x['metadata']['name'] for x in pods['items'] if x['metadata']['namespace']=='brain-apps' and x['metadata']['labels'].get('app.kubernetes.io/name')=='content-service' and x['status']['phase']=='Running')
status,body,_=call(base+'/api/v1/namespaces/brain-apps/pods/'+pod+'/log?tailLines=1');assert status==200
print('PASS authenticated Headlamp proxy lists two nodes, application Pods/deployments, metrics and Pod logs without printing resource contents',flush=True)
expect(base+'/api/v1/namespaces/brain-apps/secrets',403)
for verb,resource,subresource in [('create','pods',None),('patch','deployments',None),('delete','pods',None),('create','pods','exec'),('get','secrets',None),('create','serviceaccounts','token')]:
 attrs=dict(namespace='brain-apps',verb=verb,resource=resource,group='apps' if resource=='deployments' else '')
 if subresource:attrs['subresource']=subresource
 status,body,_=call(base+'/apis/authorization.k8s.io/v1/selfsubjectaccessreviews','POST',dict(apiVersion='authorization.k8s.io/v1',kind='SelfSubjectAccessReview',spec=dict(resourceAttributes=attrs)));assert status==201,'Authorization review failed';assert json.loads(body)['status']['allowed'] is False,verb+' '+resource+' must be denied'
print('PASS Secret read, create/patch/delete, Pod exec and service-account token issuance are denied; backend account has no cluster-admin binding',flush=True)
