import json,shutil,subprocess,time,sys,re
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 r=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if r.returncode:raise RuntimeError(r.stderr)
 return r.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
def apply(doc):return run(['apply','-f','-'],json.dumps(doc))
service=sys.argv[sys.argv.index('--service')+1] if '--service' in sys.argv else 'space'
if service not in ('identity','space','gateway','content','media','knowledge','brain-query','learning','activity','planning','notification','collaboration','integration','minio'):raise RuntimeError('Supported services: identity, space, gateway, content, media, minio')
metadata=json.loads((root/'.local'/(service+'-image.json')).read_text());build=metadata['pod']
run(['wait','-n','brain-apps','--for=condition=Ready','pod/'+build,'--timeout=60s'])
if not re.fullmatch(service+r'-image-build-[0-9]+',build) or not re.fullmatch(r'local-[0-9a-f]{16}',metadata['tag']):raise RuntimeError('Unexpected build metadata')
nodes=json.loads(run(['get','nodes','-o','json']))['items']
if '--node' in sys.argv:
 selected=sys.argv[sys.argv.index('--node')+1];nodes=[n for n in nodes if n['metadata']['name']==selected]
 if not nodes:raise RuntimeError('Unknown local node')
for i,node in enumerate(nodes):
 name=service+'-image-import-'+str(int(time.time()))+'-'+str(i)
 url='http://'+build+'.brain-apps.svc:9080'
 command='for attempt in 1 2 3 4 5; do wget -T 30 -q -O /tmp/image.tar '+url+'/image.tar && break; sleep 2; done; test -s /tmp/image.tar; for attempt in 1 2 3 4 5; do wget -T 10 -q -O /tmp/image.sha256 '+url+'/image.sha256 && break; sleep 2; done; test -s /tmp/image.sha256; digest=$(cat /tmp/image.sha256); echo "$digest" | grep -Eq "^[0-9a-f]{64}$"; echo "$digest  /tmp/image.tar" | sha256sum -c -; /bin/ctr --address=/run/k3s/containerd/containerd.sock --namespace=k8s.io images import /tmp/image.tar'
 container=dict(name='import',image='rancher/k3s:v1.35.5-k3s1',command=['/bin/sh','-ec',command],securityContext=dict(runAsUser=0,allowPrivilegeEscalation=False,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='100m',memory='128Mi'),limits=dict(cpu='1',memory='512Mi')),volumeMounts=[dict(name='containerd-socket',mountPath='/run/k3s/containerd/containerd.sock',readOnly=True)])
 apply(dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-apps'),spec=dict(backoffLimit=0,activeDeadlineSeconds=180,ttlSecondsAfterFinished=3600,template=dict(metadata=dict(labels={'local-image-import':'true'}),spec=dict(nodeName=node['metadata']['name'],restartPolicy='Never',automountServiceAccountToken=False,containers=[container],volumes=[dict(name='containerd-socket',hostPath=dict(path='/run/k3s/containerd/containerd.sock',type='Socket'))])))))
 print('IMPORT_JOB='+name+' NODE='+node['metadata']['name'],flush=True)
print('Image imports started. Wait for all printed Jobs before deploying '+metadata['image'])
