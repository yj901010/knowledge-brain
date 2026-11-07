"""Local counterpart of backend CI, using isolated ephemeral PostgreSQL only."""
import argparse,base64,hashlib,io,json,subprocess,sys,tarfile,time,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
K=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(ROOT/'.local/kubeconfig.yaml')]
def run(args,body=None):
    if args[0]=='apply':args=args[:1]+['--server-side']+args[1:]
    result=subprocess.run(K+args,input=body,text=True,capture_output=True)
    if result.returncode:raise RuntimeError(result.stderr)
    return result.stdout
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--images',action='store_true',help='Build/test all13 images and retain the running Pod for artifact collection');parser.add_argument('--frontend-proof',help='Reuse an exact-commit frontend receipt only after current source and asset hash validation');parser.add_argument('--output',default=str(ROOT/'.local/delivery/ci-job.json'));options=parser.parse_args()
    if options.frontend_proof and not options.images:parser.error('--frontend-proof requires --images')
    if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise ValueError('Local context required')
    frontend=None
    if options.images:
        subprocess.run([sys.executable,str(ROOT/'scripts/Sync-PublicCurationWeb.py')],check=True)
        output=Path(options.output);frontend_dir=output.parent/'frontend'
        if not options.frontend_proof:subprocess.run([sys.executable,str(ROOT/'scripts/Verify-DeliveryFrontend.py'),'--directory',str(frontend_dir)],check=True)
        receipt=json.loads(Path(options.frontend_proof or frontend_dir/'frontend-proof.json').read_text());frontend=Path(receipt['fixture'])/'.local/ci-web'
        sys.path.insert(0,str(ROOT/'scripts/ci'));from delivery import check_web_sources
        web=check_web_sources(frontend,receipt['fixtureSourceRevision'],ROOT/'apps/graph-web')
    lock=json.loads((ROOT/'infra/dependencies/delivery-tools.json').read_text());services=json.loads((ROOT/'scripts/ci/services.json').read_text());ns='brain-delivery-probe';name='delivery-ci-'+str(time.time_ns())
    archive=io.BytesIO();fingerprint=hashlib.sha256()
    with tarfile.open(fileobj=archive,mode='w:gz') as tar:
        for p in sorted([ROOT/'pom.xml']+[p for directory in ('services','contracts','libraries') for p in (ROOT/directory).rglob('*')]):
            if frontend and p.is_relative_to(ROOT/'services/gateway-service/src/main/resources/static/graph'):continue
            if p.is_file() and 'target' not in p.relative_to(ROOT).parts and (p.name=='pom.xml' or 'src' in p.relative_to(ROOT).parts):tar.add(p,arcname=p.relative_to(ROOT).as_posix())
            else:continue
            fingerprint.update(p.relative_to(ROOT).as_posix().encode()+b'\0'+p.read_bytes())
        for p in sorted((ROOT/'infra/runtime/legal').iterdir()):
            if p.is_file():tar.add(p,arcname=p.relative_to(ROOT).as_posix());fingerprint.update(p.relative_to(ROOT).as_posix().encode()+b'\0'+p.read_bytes())
        if frontend:
            for asset_name in sorted(web['files']):
                p=frontend/'graph'/asset_name;relative='services/gateway-service/src/main/resources/static/graph/'+asset_name;tar.add(p,arcname=relative);fingerprint.update(relative.encode()+b'\0'+p.read_bytes())
            for source_name in sorted(web['sourceFiles']):
                p=ROOT/'apps/graph-web'/source_name;relative='apps/graph-web/'+source_name;tar.add(p,arcname=relative);fingerprint.update(relative.encode()+b'\0'+p.read_bytes())
            check_web_sources(frontend,receipt['fixtureSourceRevision'],ROOT/'apps/graph-web')
        for p in (ROOT/'scripts/ci').glob('*.py'):tar.add(p,arcname=p.relative_to(ROOT).as_posix())
        tar.add(ROOT/'scripts/ci/services.json',arcname='scripts/ci/services.json')
    tag='local-'+fingerprint.hexdigest()[:16];encoded=base64.b64encode(archive.getvalue()).decode();maps=[]
    for index,offset in enumerate(range(0,len(encoded),700000)):
        cm=name+'-'+str(index);maps.append(dict(configMap=dict(name=cm)))
        run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=cm,namespace=ns),data={'part'+str(index).zfill(3):encoded[offset:offset+700000]})))
    sql='\n'.join('CREATE USER '+s['database']+" PASSWORD 'ci-ephemeral-only';\nCREATE DATABASE "+s['database']+' OWNER '+s['database']+';' for s in services)
    init=name+'-init';run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=init,namespace=ns),data={'init.sql':sql})))
    mounts=[dict(name='source',mountPath='/source',readOnly=True),dict(name='work',mountPath='/work')]
    env=[]
    for s in services:
        if s['testPrefix']:env += [dict(name=s['testPrefix']+'_DATABASE_URL',value='jdbc:postgresql://127.0.0.1:5432/'+s['database']),dict(name=s['testPrefix']+'_DATABASE_PASSWORD',value='ci-ephemeral-only')]
    command='set -o pipefail; mkdir /work/repo; cat /source/part* | base64 -d | tar xz -C /work/repo; cd /work/repo; mvn -B -ntp -Dstyle.color=never -Dmaven.repo.local=/work/m2 verify; python3 --version || true'
    # Maven image has no Python: Surefire summary is collected from the completed Pod.
    command=command.removesuffix('; python3 --version || true')
    if options.images:
        command+=' -Pcontainer-image -Dimage.tag='+tag+'; touch /work/build-passed; echo ALL_IMAGES_VERIFIED='+tag+'; sleep 2400'
    job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace=ns),spec=dict(backoffLimit=0,activeDeadlineSeconds=1500,ttlSecondsAfterFinished=86400,template=dict(spec=dict(automountServiceAccountToken=False,restartPolicy='Never',initContainers=[dict(name='postgres',restartPolicy='Always',image=lock['images']['postgres'],env=[dict(name='POSTGRES_PASSWORD',value='ci-bootstrap-only')],ports=[dict(containerPort=5432)],startupProbe=dict(exec=dict(command=['pg_isready','-U','postgres']),periodSeconds=2,failureThreshold=60),resources=dict(requests=dict(cpu='100m',memory='128Mi'),limits=dict(cpu='1',memory='512Mi')),volumeMounts=[dict(name='postgres',mountPath='/var/lib/postgresql/data'),dict(name='init',mountPath='/docker-entrypoint-initdb.d',readOnly=True)])],containers=[dict(name='verify',image=lock['images']['maven'],command=['bash','-ec',command],env=env,resources=dict(requests=dict(cpu='500m',memory='768Mi'),limits=dict(cpu='2',memory='2Gi')),volumeMounts=mounts)],volumes=[dict(name='source',projected=dict(sources=maps)),dict(name='work',emptyDir=dict(sizeLimit='3Gi')),dict(name='postgres',emptyDir=dict(sizeLimit='512Mi')),dict(name='init',configMap=dict(name=init))]))))
    if options.images:
        job['spec']['activeDeadlineSeconds']=3600;job['spec']['template']['spec']['volumes'][1]['emptyDir']['sizeLimit']='6Gi'
    run(['apply','-f','-'],json.dumps(job));uid=json.loads(run(['get','job',name,'-n',ns,'-o','json']))['metadata']['uid']
    for cm in [s['configMap']['name'] for s in maps]+[init]:run(['patch','configmap',cm,'-n',ns,'--type=merge','-p',json.dumps(dict(metadata=dict(ownerReferences=[dict(apiVersion='batch/v1',kind='Job',name=name,uid=uid)])))])
    path=Path(options.output);path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(dict(name=name,uid=uid,namespace=ns,sourceMaps=[s['configMap']['name'] for s in maps]+[init],images=options.images,tag=tag,sourceFingerprint=fingerprint.hexdigest(),archiveSha256=hashlib.sha256(archive.getvalue()).hexdigest()),indent=2));print(path.read_text())
if __name__=='__main__':main()
