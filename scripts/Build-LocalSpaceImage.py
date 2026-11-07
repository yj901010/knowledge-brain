import base64,hashlib,io,json,shutil,subprocess,tarfile,time,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 r=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if r.returncode:raise RuntimeError(r.stderr)
 return r.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
def apply(doc):return run(['apply','--server-side','-f','-'],json.dumps(doc))
service=sys.argv[sys.argv.index('--service')+1] if '--service' in sys.argv else 'space'
if service not in ('identity','space','gateway','content','media','knowledge','brain-query','learning','activity','planning','notification','collaboration','integration'):raise RuntimeError('Supported services: identity, space, gateway, content, media')
if service=='gateway':
 result=subprocess.run([sys.executable,str(root/'scripts/Build-GraphWeb.py')])
 if result.returncode:raise RuntimeError('Graph Web export required before Gateway image')
files=[root/'pom.xml']+[p for directory in ['services','contracts','libraries'] for p in (root/directory).rglob('*') if p.is_file() and 'target' not in p.relative_to(root).parts and (p.name=='pom.xml' or 'src' in p.relative_to(root).parts)]+[p for p in (root/'infra/runtime/legal').iterdir() if p.is_file()]
digest=hashlib.sha256();archive=io.BytesIO()
with tarfile.open(fileobj=archive,mode='w:gz') as tar:
 for p in sorted(files):
  relative=p.relative_to(root).as_posix();content=p.read_bytes();digest.update(relative.encode()+b'\0'+content);tar.add(p,arcname=relative)
tag='local-'+digest.hexdigest()[:16];name=service+'-image-build-'+str(int(time.time()))
# ConfigMap data is limited to 1 MiB; project bounded archive chunks into one
# source volume. This changes build transport only, not application architecture.
encoded=base64.b64encode(archive.getvalue()).decode()
chunk_size=700000
source_maps=[]
for ordinal,offset in enumerate(range(0,len(encoded),chunk_size)):
 source_name=name+'-source-'+str(ordinal).zfill(3)
 filename='source.part'+str(ordinal).zfill(3)+'.b64'
 apply(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=source_name,namespace='brain-apps'),data={filename:encoded[offset:offset+chunk_size]}))
 source_maps.append(source_name)

command='set -o pipefail; mkdir -p /tmp/build; cat /source/source*.b64 | base64 -d | tar xz -C /tmp/build; cd /tmp/build; mvn -B -Dstyle.color=never -Dmaven.repo.local=/tmp/m2 -pl services/'+service+'-service -am -Pcontainer-image -Dimage.tag='+tag+' -DskipTests package; cp services/'+service+'-service/target/jib-image.tar /artifacts/image.tar; sha256sum /artifacts/image.tar | cut -d " " -f 1 > /artifacts/image.sha256'
build=dict(name='build',image='maven:3.10.0-eclipse-temurin-25@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525',command=['bash','-ec',command],resources=dict(requests=dict(cpu='500m',memory='1Gi'),limits=dict(cpu='2',memory='2Gi')),volumeMounts=[dict(name='source',mountPath='/source',readOnly=True),dict(name='artifacts',mountPath='/artifacts')])
serve=dict(name='artifacts',image='python:3.13.7-alpine',command=['python','-m','http.server','9080','--directory','/artifacts'],securityContext=dict(runAsUser=10001,runAsNonRoot=True,allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='25m',memory='32Mi'),limits=dict(cpu='250m',memory='96Mi')),ports=[dict(name='artifacts',containerPort=9080)],readinessProbe=dict(httpGet=dict(path='/image.sha256',port='artifacts'),periodSeconds=5),volumeMounts=[dict(name='artifacts',mountPath='/artifacts',readOnly=True)])
apply(dict(apiVersion='v1',kind='Pod',metadata=dict(name=name,namespace='brain-apps',labels={'app':'space-image-artifacts','build-id':name}),spec=dict(restartPolicy='Never',activeDeadlineSeconds=900,automountServiceAccountToken=False,securityContext=dict(fsGroup=10001),initContainers=[build],containers=[serve],volumes=[dict(name='source',projected={'sources':[{'configMap':{'name':source_name}} for source_name in source_maps]}),dict(name='artifacts',emptyDir=dict(sizeLimit='2Gi'))])))
# Keep temporary chunks owned by this exact builder Pod for automatic cleanup.
pod_uid=json.loads(run(['get','pod',name,'-n','brain-apps','-o','json']))['metadata']['uid']
for source_name in source_maps:
 run(['patch','configmap',source_name,'-n','brain-apps','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'v1','kind':'Pod','name':name,'uid':pod_uid}]}})])
apply(dict(apiVersion='v1',kind='Service',metadata=dict(name=name,namespace='brain-apps'),spec=dict(selector={'app':'space-image-artifacts','build-id':name},ports=[dict(name='artifacts',port=9080,targetPort='artifacts')])))
# Same-namespace import Jobs only; this temporary endpoint exposes built image layers, no credentials.
policy=dict(apiVersion='networking.k8s.io/v1',kind='NetworkPolicy',metadata=dict(name=name,namespace='brain-apps'),spec=dict(podSelector=dict(matchLabels={'app':'space-image-artifacts','build-id':name}),policyTypes=['Ingress'],ingress=[{'from':[{'podSelector':{'matchLabels':{'local-image-import':'true'}}}],'ports':[{'protocol':'TCP','port':9080}]}]))
apply(policy)
metadata=dict(sourceConfigMaps=source_maps,pod=name,tag=tag,image='knowledge-brain/'+service+'-service:'+tag)
(root/'.local'/ (service+'-image.json')).write_text(json.dumps(metadata,indent=2))
print('BUILD_POD='+name);print('LOCAL_IMAGE='+metadata['image'])
