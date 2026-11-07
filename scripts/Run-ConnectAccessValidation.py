import json,shutil,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 r=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if r.returncode:raise RuntimeError(r.stderr)
 return r.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
def apply(doc):return run(['apply','-f','-'],json.dumps(doc))
script="""import os,json,socket,urllib.request,urllib.error
allowed=os.environ['EXPECTED_ACCESS']=='allowed'
socket.getaddrinfo('brain-connect.brain-events.svc',8083)
try:
 with urllib.request.urlopen('http://brain-connect.brain-events.svc:8083/connectors/identity-outbox/status',timeout=5) as r:
  status=json.load(r)
 assert allowed,'Unlabeled pod unexpectedly reached Connect REST'
 assert status['connector']['state']=='RUNNING' and status['tasks'] and all(t['state']=='RUNNING' for t in status['tasks'])
 print('PASS labeled administrator can reach RUNNING connector/task')
except urllib.error.HTTPError:raise
except (TimeoutError,urllib.error.URLError):
 assert not allowed,'Administrator access failed'
 print('PASS unlabeled pod cannot reach Connect REST')
"""
for expected in ['allowed','denied']:
 name='connect-access-'+expected+'-'+str(int(time.time()))
 apply(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-data'),data={'verify.py':script}))
 labels={'brain-connect-admin':'true'} if expected=='allowed' else {}
 apply(dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=30,ttlSecondsAfterFinished=86400,template=dict(metadata=dict(labels=labels),spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001),containers=[dict(name='verify',image='python:3.13.7-alpine',command=['python','/scripts/verify.py'],env=[dict(name='EXPECTED_ACCESS',value=expected)],securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='25m',memory='32Mi'),limits=dict(cpu='100m',memory='64Mi')),volumeMounts=[dict(name='scripts',mountPath='/scripts',readOnly=True)])],volumes=[dict(name='scripts',configMap=dict(name=name))])))))
 job=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))
 run(['patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':job['metadata']['uid']}]}})])
 print('JOB='+name)
