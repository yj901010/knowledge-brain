"""Actual local Git -> Argo -> Kubernetes sync/drift/rollback, isolated from domain apps."""
import base64,hashlib,json,subprocess,shutil,time,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];NS='brain-delivery';TARGET='brain-delivery-probe'
K=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(ROOT/'.local/kubeconfig.yaml')]
def run(args,body=None):
    result=subprocess.run(K+args,input=body,text=True,capture_output=True)
    if result.returncode:raise RuntimeError(result.stderr)
    return result.stdout
def apply(doc):return run(['apply','--server-side','-f','-'],json.dumps(doc))
def get(kind,name,namespace=NS):return json.loads(run(['get',kind,name,'-n',namespace,'-o','json']))
def wait(predicate,description,seconds=240):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        if predicate():return
        time.sleep(3)
    raise TimeoutError(description)
def git(path,*args):
    return subprocess.run(['git','-C',str(path),'-c','safe.directory='+str(path),'-c','core.autocrlf=false','-c','commit.gpgsign=false']+list(args),check=True,capture_output=True,text=True,encoding='utf-8').stdout.strip()
def setup():
    if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise ValueError('Local context required')
    current=json.loads(run(['get','deployment','gateway-service','-n','brain-apps','-o','json']))['spec']['template']['spec']['containers'][0]['image']
    # Resolve the OCI manifest, not the config/imageID. Cache alias leaves original tag intact.
    rows=subprocess.check_output(['docker','exec','k3d-knowledge-brain-server-0','ctr','-n','k8s.io','images','ls','name==docker.io/'+current],text=True).splitlines()
    digest=rows[1].split()[2]
    if not digest.startswith('sha256:') or len(digest)!=71:raise ValueError('OCI manifest digest unavailable')
    repository='docker.io/knowledge-brain/gateway-service';alias=repository+'@'+digest
    for node in ['k3d-knowledge-brain-server-0','k3d-knowledge-brain-agent-0']:
        existing=subprocess.check_output(['docker','exec',node,'ctr','-n','k8s.io','images','ls','-q','name=='+alias],text=True).strip()
        if not existing:subprocess.run(['docker','exec',node,'ctr','-n','k8s.io','images','tag','docker.io/'+current,alias],check=True,capture_output=True)
    directory=ROOT/'.local/delivery/fixtures'/uuid.uuid4().hex;work=directory/'work';work.mkdir(parents=True)
    shutil.copytree(ROOT/'infra/charts/brain-service',work/'chart')
    git(work,'init','-b','main');git(work,'config','user.name','Local delivery drill');git(work,'config','user.email','delivery-drill@localhost.invalid')
    commits=[]
    for mark in ['A','B','A']:
        values=dict(replicaCount=1,image=dict(repository=repository,tag='',digest=digest,pullPolicy='Never'),resources=dict(requests=dict(cpu='50m',memory='128Mi'),limits=dict(cpu='500m',memory='384Mi')),env=[dict(name='BRAIN_GATEWAY_API_ENABLED',value='false'),dict(name='BRAIN_DELIVERY_GENERATION',value=mark)])
        (work/'probe.json').write_text(json.dumps(values,indent=2)+'\n');git(work,'add','.');git(work,'commit','--allow-empty','-m','Probe generation '+mark);commits.append(git(work,'rev-parse','HEAD'));git(work,'tag','probe-'+str(len(commits)))
    bare=directory/'probe.git';subprocess.run(['git','clone','--bare',str(work),str(bare)],check=True,capture_output=True);git(bare,'update-server-info')
    files={'/probe.git/'+p.relative_to(bare).as_posix():base64.b64encode(p.read_bytes()).decode() for p in bare.rglob('*') if p.is_file() and 'hooks' not in p.relative_to(bare).parts and p.name not in ['config','description']}
    fixture_sha=hashlib.sha256(json.dumps(files,sort_keys=True).encode()).hexdigest()
    server="const fs=require('fs'),http=require('http');const files=JSON.parse(fs.readFileSync('/fixture/files.json'));http.createServer((req,res)=>{let body=files[new URL(req.url,'http://localhost').pathname];if(!['GET','HEAD'].includes(req.method)||body===undefined){res.writeHead(404);return res.end();}body=Buffer.from(body,'base64');res.writeHead(200,{'Content-Length':body.length,'Content-Type':'application/octet-stream'});res.end(req.method==='HEAD'?undefined:body);}).listen(8080,'0.0.0.0');"
    apply(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name='delivery-git-fixture',namespace=NS),data={'files.json':json.dumps(files),'server.js':server}))
    lock=json.loads((ROOT/'infra/dependencies/delivery-tools.json').read_text())
    apply(dict(apiVersion='apps/v1',kind='Deployment',metadata=dict(name='delivery-git-fixture',namespace=NS),spec=dict(replicas=1,selector=dict(matchLabels={'app':'delivery-git-fixture'}),template=dict(metadata=dict(labels={'app':'delivery-git-fixture'},annotations={'brain.knowledge/fixture-sha256':fixture_sha}),spec=dict(automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,runAsGroup=10001),containers=[dict(name='readonly-git',image=lock['images']['node'],command=['node','/fixture/server.js'],securityContext=dict(readOnlyRootFilesystem=True,allowPrivilegeEscalation=False,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='10m',memory='32Mi'),limits=dict(cpu='200m',memory='96Mi')),ports=[dict(containerPort=8080)],readinessProbe=dict(tcpSocket=dict(port=8080)),volumeMounts=[dict(name='fixture',mountPath='/fixture',readOnly=True)])],volumes=[dict(name='fixture',configMap=dict(name='delivery-git-fixture'))])))))
    apply(dict(apiVersion='v1',kind='Service',metadata=dict(name='delivery-git-fixture',namespace=NS),spec=dict(selector=dict(app='delivery-git-fixture'),ports=[dict(port=8080,targetPort=8080)])))
    apply(dict(apiVersion='networking.k8s.io/v1',kind='NetworkPolicy',metadata=dict(name='delivery-git-fixture',namespace=NS),spec=dict(podSelector=dict(matchLabels=dict(app='delivery-git-fixture')),policyTypes=['Ingress'],ingress=[dict(ports=[dict(port=8080)],**{'from':[dict(podSelector=dict(matchLabels={'app.kubernetes.io/name':'argocd-repo-server'}))]})])))
    # The read-only server loads files.json once at startup. A new fixture must
    # start a new Pod before Argo is pointed at the new commit objects.
    run(['rollout','status','deployment/delivery-git-fixture','-n',NS,'--timeout=180s'])
    check="fetch('http://127.0.0.1:8080/probe.git/info/refs').then(r=>r.text()).then(s=>{if(!"+json.dumps(commits)+".every(id=>s.includes(id)))process.exit(1)})"
    run(['exec','-n',NS,'deployment/delivery-git-fixture','--','node','-e',check])
    repo='http://delivery-git-fixture.'+NS+'.svc:8080/probe.git'
    apply(dict(apiVersion='argoproj.io/v1alpha1',kind='AppProject',metadata=dict(name='delivery-probe',namespace=NS),spec=dict(sourceRepos=[repo],destinations=[dict(server='https://kubernetes.default.svc',namespace=TARGET)],clusterResourceWhitelist=[],namespaceResourceWhitelist=[dict(group='apps',kind='Deployment'),dict(group='',kind='Service')])) )
    # sync() owns revision changes through merge-patch. Re-applying a fresh
    # fixture must preserve that field here, then let sync() move it explicitly.
    # Matching the existing value avoids taking another manager's field by force.
    existing=run(['get','application','delivery-probe','-n',NS,'--ignore-not-found','-o','json']).strip()
    revision=commits[0]
    if existing:
        app=json.loads(existing);spec=app['spec']
        assert spec['project']=='delivery-probe' and spec['destination']==dict(server='https://kubernetes.default.svc',namespace=TARGET) and spec['source']['repoURL']==repo,'Refusing to reconfigure an unrelated Application'
        revision=spec['source']['targetRevision']
    apply(dict(apiVersion='argoproj.io/v1alpha1',kind='Application',metadata=dict(name='delivery-probe',namespace=NS),spec=dict(project='delivery-probe',source=dict(repoURL=repo,targetRevision=revision,path='chart',helm=dict(releaseName='delivery-probe',valueFiles=['../probe.json'])),destination=dict(server='https://kubernetes.default.svc',namespace=TARGET),syncPolicy=dict(syncOptions=['FailOnSharedResource=true']))))
    result=dict(commits=commits,image=alias,fixture=str(directory),repository=repo);(ROOT/'.local/delivery/drill.json').write_text(json.dumps(result,indent=2));return result
def sync(revision):
    previous=get('application','delivery-probe').get('status',{}).get('operationState',{})
    run(['annotate','application','delivery-probe','-n',NS,'argocd.argoproj.io/refresh=hard','--overwrite'])
    run(['patch','application','delivery-probe','-n',NS,'--type=merge','-p',json.dumps(dict(spec=dict(source=dict(targetRevision=revision)),operation=dict(sync=dict(revision=revision,prune=False))))])
    def done():
        status=get('application','delivery-probe').get('status',{});op=status.get('operationState',{})
        if op==previous:return False
        if op.get('phase') in ['Failed','Error']:raise RuntimeError(json.dumps(op))
        return op.get('phase')=='Succeeded' and op.get('syncResult',{}).get('revision')==revision and status.get('sync',{}).get('status')=='Synced' and status.get('health',{}).get('status')=='Healthy'
    wait(done,'Git sync '+revision,360)
    deployment=get('deployment','delivery-probe',TARGET);return dict(revision=revision,uid=deployment['metadata']['uid'],generation=deployment['metadata']['generation'],env=deployment['spec']['template']['spec']['containers'][0]['env'],image=deployment['spec']['template']['spec']['containers'][0]['image'])
def drill(state):
    results=[sync(state['commits'][0])]
    run(['set','env','deployment/delivery-probe','BRAIN_DELIVERY_GENERATION=DRIFT','-n',TARGET]);run(['annotate','application','delivery-probe','-n',NS,'argocd.argoproj.io/refresh=hard','--overwrite'])
    wait(lambda:get('application','delivery-probe').get('status',{}).get('sync',{}).get('status')=='OutOfSync','Drift detection')
    results.append(sync(state['commits'][0]));results.append(sync(state['commits'][1]));results.append(sync(state['commits'][2]))
    assert len({r['uid'] for r in results})==1
    for result,expected in zip(results,['A','A','B','A']):assert next(e['value'] for e in result['env'] if e['name']=='BRAIN_DELIVERY_GENERATION')==expected
    state['results']=results;state['driftDetected']=True;state['gitRevertRollbackPassed']=True;(ROOT/'.local/delivery/drill.json').write_text(json.dumps(state,indent=2));print('PASS Git sync, drift detection/recovery, upgrade and Git-revert rollback; Deployment UID retained')
if __name__=='__main__':
    import sys
    if '--resume' in sys.argv:drill(json.loads((ROOT/'.local/delivery/drill.json').read_text()))
    else:drill(setup())
