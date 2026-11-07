import json,shutil,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[1];k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 r=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if r.returncode:raise RuntimeError(r.stderr)
 return r.stdout
assert run(['config','current-context']).strip()=='k3d-knowledge-brain'
meta=json.loads((root/'.local/minio-image.json').read_text());name='minio-private-bootstrap-'+str(int(time.time()));env=[]
for variable,secret,key in [('ROOT_USER','brain-minio-root','accessKey'),('ROOT_PASSWORD','brain-minio-root','secretKey'),('MEDIA_ACCESS_KEY','brain-media-storage','accessKey'),('MEDIA_SECRET_KEY','brain-media-storage','secretKey')]:env.append(dict(name=variable,valueFrom=dict(secretKeyRef=dict(name=secret,key=key))))
job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=120,ttlSecondsAfterFinished=86400,template=dict(metadata=dict(labels={'media-storage-operator':'true'}),spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,runAsGroup=10001),containers=[dict(name='bootstrap',image=meta['image'],imagePullPolicy='Never',command=['/bootstrap'],env=env,securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='50m',memory='64Mi'),limits=dict(cpu='250m',memory='128Mi')),volumeMounts=[dict(name='trust',mountPath='/trust',readOnly=True)])],volumes=[dict(name='trust',secret=dict(secretName='brain-guest-api-trust'))]))))
run(['apply','-f','-'],json.dumps(job));print('JOB='+name)
