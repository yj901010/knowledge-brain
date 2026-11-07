"""Shared CI/release commands. Inputs are data, never interpolated shell code."""
import argparse,hashlib,json,os,re,shutil,subprocess,sys,tarfile,tempfile,xml.etree.ElementTree as ET
from pathlib import Path,PurePosixPath
from datetime import datetime,timezone

ROOT=Path(__file__).resolve().parents[2]
SERVICES=json.loads((ROOT/'scripts/ci/services.json').read_text())
BY_NAME={s['name']:s for s in SERVICES}
SHA=re.compile(r'^[0-9a-f]{40}$')
DIGEST=re.compile(r'^sha256:[0-9a-f]{64}$')
REPOSITORY=re.compile(r'^[a-z0-9][a-z0-9_.-]*/[a-z0-9][a-z0-9_.-]*$')
def run(args,**kwargs):return subprocess.run([str(v) for v in args],cwd=ROOT,check=True,**kwargs)
def maven(args,env=None):
    env=env or os.environ.copy();image=env.get('CI_MAVEN_IMAGE')
    if image:
        expected=json.loads((ROOT/'infra/dependencies/delivery-tools.json').read_text())['images']['maven']
        if image!=expected:raise ValueError('Reviewed Maven/JDK25 image required')
        command=['docker','run','--rm','--network','host','-v',str(ROOT)+':/workspace','-w','/workspace']
        if hasattr(os,'getuid'):command+=['--user',str(os.getuid())+':'+str(os.getgid())]
        # An unmapped hosted-runner UID has user.home=/ in this image. Keep the
        # entrypoint configuration and Jib's JVM-home cache inside the checkout.
        # Explicit values also prevent host Maven options reaching the container.
        home='/workspace/.local/ci-home'
        command+=['--env','HOME='+home,'--env','MAVEN_CONFIG='+home+'/.m2',
                  '--env','MAVEN_OPTS=-Duser.home='+home]
        for key in sorted(env):
            if key.startswith('BRAIN_TEST_'):command+=['-e',key]
        command += [image,'mvn','-Dmaven.repo.local=/workspace/.local/ci-m2']+args
        return run(command,env=env)
    return run(['mvn']+args,env=env)
def write(path,data):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
def revision(expected=None):
    value=run(['git','rev-parse','HEAD'],capture_output=True,text=True).stdout.strip()
    if not SHA.fullmatch(value) or expected and value!=expected:raise ValueError('Exact source revision required')
    return value
def matrix():return {'include':SERVICES}
def files_hashes(directory):
    result={}
    for p in sorted(Path(directory).rglob('*')):
        if p.is_symlink():raise ValueError('Artifact symlinks are forbidden')
        if p.is_file():result[p.relative_to(directory).as_posix()]=hashlib.sha256(p.read_bytes()).hexdigest()
    return result
def frontend_sources(directory):
    directory=Path(directory);result={};ignored={'node_modules','.next','out','tests','.git'}
    if directory.is_symlink():raise ValueError('Frontend source symlinks are forbidden')
    for current,dirs,files in os.walk(directory):
        dirs[:]=sorted(name for name in dirs if name not in ignored)
        for name in dirs:
            if (Path(current)/name).is_symlink():raise ValueError('Frontend source symlinks are forbidden')
        for name in sorted(files):
            if name.endswith('.tsbuildinfo'):continue
            path=Path(current)/name
            if path.is_symlink():raise ValueError('Frontend source symlinks are forbidden')
            result[path.relative_to(directory).as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest()
    return result
def check_web(directory,expected):
    directory=Path(directory);meta=json.loads((directory/'manifest.json').read_text())
    if meta.get('sourceRevision')!=expected or not SHA.fullmatch(expected):raise ValueError('Frontend source revision mismatch')
    actual=files_hashes(directory/'graph')
    if not actual or actual!=meta.get('files') or 'index.html' not in actual or 'csp.txt' not in actual:raise ValueError('Frontend artifact contents mismatch')
    for name in actual:
        if PurePosixPath(name).is_absolute() or '..' in PurePosixPath(name).parts or '\\' in name:raise ValueError('Unsafe artifact path')
    return meta
def frontend():
    source=revision();inputs=frontend_sources(ROOT/'apps/graph-web');generated=ROOT/'services/gateway-service/src/main/resources/static/graph'
    if generated.exists():
        if generated.is_symlink() or not generated.resolve().is_relative_to(ROOT):raise ValueError('Unsafe generated asset target')
        shutil.rmtree(generated)
    run([sys.executable,ROOT/'scripts/Build-GraphWeb.py'])
    output=ROOT/'.local/ci-web';output.mkdir(parents=True,exist_ok=True)
    target=output/'graph'
    if target.exists():
        assert target.resolve().is_relative_to((ROOT/'.local').resolve()) and not target.is_symlink();shutil.rmtree(target)
    shutil.copytree(ROOT/'services/gateway-service/src/main/resources/static/graph',target)
    write(output/'manifest.json',dict(sourceRevision=source,sourceFiles=inputs,files=files_hashes(target)))

def check_web_sources(directory,expected,source_directory):
    meta=check_web(directory,expected)
    if not meta.get('sourceFiles') or meta['sourceFiles']!=frontend_sources(source_directory):raise ValueError('Frontend sources changed since the verified asset build')
    return meta
def restore_web(directory,expected):
    check_web(directory,expected)
    target=ROOT/'services/gateway-service/src/main/resources/static/graph'
    if target.exists():
        if target.is_symlink() or not target.resolve().is_relative_to(ROOT):raise ValueError('Unsafe generated asset target')
        shutil.rmtree(target)
    target.parent.mkdir(parents=True,exist_ok=True);shutil.copytree(Path(directory)/'graph',target)
def reports(service):
    executed=skipped=failures=errors=0
    for path in (ROOT/BY_NAME[service]['module']/'target/surefire-reports').glob('TEST-*.xml'):
        root=ET.parse(path).getroot();total=int(root.get('tests',0));skip=int(root.get('skipped',0));executed+=total-skip;skipped+=skip;failures+=int(root.get('failures',0));errors+=int(root.get('errors',0))
    if not executed or failures or errors:raise ValueError('No successful executed test evidence: '+service)
    data=dict(service=service,executed=executed,skipped=skipped,failures=failures,errors=errors,kafkaIntegrationConfigured=False)
    write(ROOT/'.local/ci-reports'/f'{service}.json',data);return data
def backend(service):
    item=BY_NAME[service];env=os.environ.copy()
    if item['testPrefix']:
        env[item['testPrefix']+'_DATABASE_URL']='jdbc:postgresql://127.0.0.1:5432/'+item['database']
        env[item['testPrefix']+'_DATABASE_PASSWORD']=env.get('CI_DATABASE_PASSWORD','ci-ephemeral-only')
    maven(['-B','-ntp','-Dstyle.color=never','-pl',item['module'],'-am','verify'],env=env)
    print(json.dumps(reports(service)))
def validate_lock(value,repository=None):
    if value.get('schemaVersion')!=1 or not SHA.fullmatch(str(value.get('sourceRevision',''))):raise ValueError('Invalid release revision/schema')
    repo=value.get('repository','')
    if not REPOSITORY.fullmatch(repo) or repository and repo!=repository:raise ValueError('Repository mismatch')
    images=value.get('images',{})
    if set(images)!=set(BY_NAME):raise ValueError('All thirteen independent service images are required')
    for name,item in images.items():
        if item.get('repository')!='ghcr.io/'+repo+'/'+name or not DIGEST.fullmatch(str(item.get('digest',''))):raise ValueError('Service image is not digest pinned')
    return value
def assemble(directory,repository,source):
    if not SHA.fullmatch(source) or not REPOSITORY.fullmatch(repository):raise ValueError('Invalid release identity')
    images={}
    for path in Path(directory).rglob('image.json'):
        data=json.loads(path.read_text());name=data.get('service')
        if name not in BY_NAME or name in images or data.get('sourceRevision')!=source or data.get('scanPassed') is not True:raise ValueError('Duplicate, foreign or unscanned image artifact')
        images[name]=dict(repository=data['repository'],digest=data['digest'])
    return validate_lock(dict(schemaVersion=1,repository=repository,sourceRevision=source,createdAt=datetime.now(timezone.utc).isoformat(),images=images))
def project_legal_hashes():
    result={}
    for name in ['LICENSE','NOTICE']:
        source=(ROOT/name).read_bytes();mirror=ROOT/'infra/runtime/legal'/('project-'+name+'.txt')
        if not source or len(source)>65536 or mirror.read_bytes()!=source:raise ValueError('Project legal source and image copy must match')
        result['/licenses/knowledge-brain/project-'+name+'.txt']=hashlib.sha256(source).hexdigest()
    return result
def image_legal_hashes(archive):
    """Inspect only the two project legal files in the final layered Jib image."""
    expected=project_legal_hashes();wanted={name.lstrip('/'):value for name,value in expected.items()}
    parents={str(parent) for name in wanted for parent in PurePosixPath(name).parents if str(parent)!='.'}
    state={name:None for name in wanted};parent_types={}
    def contains(directory,path):return path==directory or path.startswith(directory+'/')
    def remove(directory):
        for name in state:
            if contains(directory,name):state[name]=None
        for name in list(parent_types):
            if contains(directory,name):parent_types.pop(name)
    with tarfile.open(archive,'r:*') as outer:
        members={}
        for member in outer:members.setdefault(member.name,[]).append(member)
        def entry(name):
            values=members.get(name,[])
            if len(values)!=1 or not values[0].isfile():raise ValueError('Ambiguous or nonregular Jib archive member')
            return values[0]
        manifest_entry=entry('manifest.json')
        if manifest_entry.size>1048576:raise ValueError('Jib image manifest exceeds bound')
        manifest=json.loads(outer.extractfile(manifest_entry).read())
        if not isinstance(manifest,list) or len(manifest)!=1:raise ValueError('One Jib Docker image is required')
        layers=manifest[0].get('Layers')
        if not isinstance(layers,list) or not layers or not all(isinstance(name,str) for name in layers):raise ValueError('Jib image layers missing')
        for layer_name in layers:
            actions=[];whiteouts=[]
            with tarfile.open(fileobj=outer.extractfile(entry(layer_name)),mode='r|*') as layer:
                for member in layer:
                    path=PurePosixPath(member.name)
                    if '..' in path.parts or '\\' in member.name:raise ValueError('Ambiguous Jib image path')
                    name=str(path).lstrip('/');base=path.name;parent=str(PurePosixPath(name).parent)
                    if base.startswith('.wh.'):
                        deleted=parent if base=='.wh..wh..opq' else str(PurePosixPath(name).with_name(base[4:]))
                        if deleted=='.':deleted=''
                        whiteouts.append(deleted);continue
                    if name in parents:actions.append((name,'directory' if member.isdir() else 'other',None))
                    elif name in wanted:
                        value=None
                        if member.isfile() and member.size<=65536:value=hashlib.sha256(layer.extractfile(member).read()).hexdigest()
                        actions.append((name,'file',value))
                    elif any(contains(target,name) for target in wanted):actions.append((name,'child',None))
            # Whiteouts apply to lower layers, independent of tar entry ordering.
            for deleted in whiteouts:
                if not deleted:
                    for name in state:state[name]=None
                    parent_types.clear()
                else:remove(deleted)
            for name,kind,value in actions:
                if kind=='directory':parent_types[name]=kind
                elif kind=='other':remove(name);parent_types[name]=kind
                elif kind=='file':state[name]=value if all(parent_types.get(str(p))!='other' for p in PurePosixPath(name).parents) else None
                else:
                    for target in state:
                        if contains(target,name):state[target]=None
    if state!=wanted:raise ValueError('Final image project LICENSE/NOTICE are missing, nonregular or changed')
    return expected
def build_image(service,repository,source):
    revision(source)
    if not REPOSITORY.fullmatch(repository):raise ValueError('Invalid GHCR repository')
    module=BY_NAME[service]['module'];target=ROOT/module/'target';image='ghcr.io/'+repository+'/'+service
    for name in ['scan-proof.json','image.json']:
        stale=target/name
        if stale.exists():stale.unlink()
    maven(['-B','-ntp','-DskipTests','-pl',module,'-am','install'])
    maven(['-B','-ntp','-f',module+'/pom.xml','-Pcontainer-image','-DskipTests','-Dimage.repository='+image,'-Dimage.tag=sha-'+source,'package'])
    tar=target/'jib-image.tar'
    if not tar.is_file():raise ValueError('Jib image archive missing')
    image_hash=hashlib.sha256(tar.read_bytes()).hexdigest();legal=image_legal_hashes(tar)
    trivy=ROOT/'.local/ci-tools/trivy'
    run([trivy,'image','--input',tar,'--scanners','vuln','--severity','HIGH,CRITICAL','--exit-code','1','--format','json','--output',target/'trivy.json'])
    if hashlib.sha256(tar.read_bytes()).hexdigest()!=image_hash:raise ValueError('Image changed during legal/scan validation')
    write(target/'scan-proof.json',dict(sourceRevision=source,service=service,repository=image,scanPassed=True,imageTarSha256=image_hash,reportSha256=hashlib.sha256((target/'trivy.json').read_bytes()).hexdigest(),legalFiles=legal))
def publish(service,repository,source):
    revision(source);target=ROOT/BY_NAME[service]['module']/'target';proof=json.loads((target/'scan-proof.json').read_text())
    if proof.get('sourceRevision')!=source or proof.get('service')!=service or proof.get('repository')!='ghcr.io/'+repository+'/'+service or not proof.get('scanPassed'):raise ValueError('Verified image scan required')
    tar=target/'jib-image.tar'
    if hashlib.sha256(tar.read_bytes()).hexdigest()!=proof['imageTarSha256'] or hashlib.sha256((target/'trivy.json').read_bytes()).hexdigest()!=proof['reportSha256']:raise ValueError('Image or scan changed after validation')
    if proof.get('legalFiles')!=project_legal_hashes():raise ValueError('Current project legal proof required')
    user=os.environ['GHCR_USER'];token=os.environ['GHCR_TOKEN'];image=proof['repository']+':sha-'+source
    with tempfile.TemporaryDirectory(prefix='brain-ghcr-') as directory:
        env=os.environ.copy();env['DOCKER_CONFIG']=directory
        run(['docker','login','ghcr.io','--username',user,'--password-stdin'],input=token,text=True,env=env)
        run(['docker','load','--input',tar],env=env);run(['docker','push',image],env=env)
        digests=json.loads(run(['docker','image','inspect',image,'--format','{{json .RepoDigests}}'],capture_output=True,text=True,env=env).stdout)
        values=[v.split('@',1)[1] for v in digests if v.startswith(proof['repository']+'@')]
        if len(set(values))!=1 or not DIGEST.fullmatch(values[0]):raise ValueError('Pushed manifest digest missing')
    write(target/'image.json',dict(service=service,repository=proof['repository'],digest=values[0],sourceRevision=source,scanPassed=True,legalFiles=proof['legalFiles']))
def main():
    p=argparse.ArgumentParser();p.add_argument('command',choices=['matrix','frontend','restore-web','backend','build-image','publish','assemble','assert-revision','release-source']);p.add_argument('--service',choices=list(BY_NAME));p.add_argument('--repository');p.add_argument('--revision');p.add_argument('--directory');p.add_argument('--branch');p.add_argument('--output',default='.local/release-lock.json');a=p.parse_args()
    if a.command=='matrix':print(json.dumps(matrix(),separators=(',',':')))
    elif a.command=='frontend':frontend()
    elif a.command=='restore-web':restore_web(a.directory,revision(a.revision))
    elif a.command=='backend':backend(a.service)
    elif a.command=='build-image':build_image(a.service,a.repository,a.revision)
    elif a.command=='publish':publish(a.service,a.repository,a.revision)
    elif a.command=='assemble':write(ROOT/a.output,assemble(a.directory,a.repository,a.revision))
    elif a.command=='assert-revision':print(revision(a.revision))
    elif a.command=='release-source':
        revision(a.revision)
        if not a.branch or not re.fullmatch(r'[A-Za-z0-9_./-]+',a.branch) or '..' in a.branch:raise ValueError('Valid default branch required')
        run(['git','merge-base','--is-ancestor',a.revision,'refs/remotes/origin/'+a.branch])
if __name__=='__main__':main()
