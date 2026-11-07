"""Build the same pinned static frontend in the existing local Linux tool image."""
import base64,io,json,shutil,subprocess,tarfile,time
from pathlib import Path

def export(root):
 k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
 def run(args,body=None):
  r=subprocess.run(k+args,input=body,text=True,encoding='utf-8',capture_output=True)
  if r.returncode:raise RuntimeError('Local frontend build command failed')
  return r.stdout
 assert run(['config','current-context']).strip()=='k3d-knowledge-brain'
 name='graph-static-build-'+str(time.time_ns());source=root/'apps/graph-web';archive=io.BytesIO()
 with tarfile.open(fileobj=archive,mode='w:gz') as tar:
  for p in source.rglob('*'):
   if p.is_file() and not any(part in ('node_modules','.next','out','tests') for part in p.relative_to(source).parts):tar.add(p,arcname=p.relative_to(source).as_posix())
 shell="""set -e
export NEXT_TELEMETRY_DISABLED=1
mkdir -p /tmp/build
base64 -d /source/source.b64 | tar xz -C /tmp/build
cd /tmp/build
npm ci --no-audit --no-fund
npm run build
mkdir -p /tmp/export
cp -R out /tmp/export/out
node --input-type=commonjs <<'NODE'
const fs=require('fs'),path=require('path'),lock=JSON.parse(fs.readFileSync('package-lock.json','utf8'));
for(const [name,item] of Object.entries(lock.packages)){
 if(!name || item.dev || !fs.existsSync(name))continue;
 for(const file of fs.readdirSync(name))if(/^(license|copying|notice)([._-]|$)/i.test(file)&&fs.statSync(path.join(name,file)).isFile()){
  const target=path.join('/tmp/export',name);fs.mkdirSync(target,{recursive:true});fs.copyFileSync(path.join(name,file),path.join(target,file));
 }
}
NODE
printf 'STATIC_EXPORT_ARCHIVE='
tar cz -C /tmp/export . | base64 -w0
printf '\n'
"""
 cm={'apiVersion':'v1','kind':'ConfigMap','metadata':{'name':name,'namespace':'brain-data'},'data':{'source.b64':base64.b64encode(archive.getvalue()).decode(),'run.sh':shell}}
 run(['apply','--server-side','-f','-'],json.dumps(cm))
 job={'apiVersion':'batch/v1','kind':'Job','metadata':{'name':name,'namespace':'brain-data'},'spec':{'backoffLimit':0,'activeDeadlineSeconds':600,'ttlSecondsAfterFinished':86400,'template':{'metadata':{'labels':{'brain-guest-api-validation':'true'}},'spec':{'restartPolicy':'Never','automountServiceAccountToken':False,'containers':[{'name':'build','image':'mcr.microsoft.com/playwright:v1.64.0-noble','command':['bash','/source/run.sh'],'securityContext':{'allowPrivilegeEscalation':False},'resources':{'requests':{'cpu':'250m','memory':'512Mi'},'limits':{'cpu':'2','memory':'2Gi'}},'volumeMounts':[{'name':'source','mountPath':'/source','readOnly':True}]}],'volumes':[{'name':'source','configMap':{'name':name}}]}}}}
 run(['apply','-f','-'],json.dumps(job));uid=json.loads(run(['get','job/'+name,'-n','brain-data','-o','json']))['metadata']['uid']
 run(['patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':uid}]}})])
 print('STATIC_BUILD_JOB='+name,flush=True)
 for attempt in range(120):
  state=json.loads(run(['get','job/'+name,'-n','brain-data','-o','json'])).get('status',{})
  if state.get('succeeded') or state.get('failed'):break
  time.sleep(5)
 logs=run(['logs','job/'+name,'-n','brain-data']);encoded=None
 for line in logs.splitlines():
  if line.startswith('STATIC_EXPORT_ARCHIVE='):encoded=line.split('=',1)[1]
  else:print(line,flush=True)
 if not state.get('succeeded') or not encoded:raise RuntimeError('Local Linux static export failed')
 data=base64.b64decode(encoded,validate=True)
 if len(data)>32*1024*1024:raise RuntimeError('Unexpected export size')
 web=root/'.local'/name;web.mkdir(parents=True,exist_ok=False)
 with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as tar:
  for member in tar:
   path=Path(member.name);target=(web/path).resolve()
   if not target.is_relative_to(web.resolve()) or member.issym() or member.islnk():raise RuntimeError('Unsafe export path')
   if member.isdir():target.mkdir(parents=True,exist_ok=True)
   elif member.isfile():
    target.parent.mkdir(parents=True,exist_ok=True)
    with tar.extractfile(member) as src:target.write_bytes(src.read())
   else:raise RuntimeError('Unsupported export member')
 shutil.copyfile(source/'package-lock.json',web/'package-lock.json')
 (root/'.local/graph-static-build.json').write_text(json.dumps({'job':name,'namespace':'brain-data','export':str(web)}),encoding='utf-8')
 return web
