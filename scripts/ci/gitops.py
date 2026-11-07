"""Prepare a reviewable, immutable GitOps promotion; never connects to a cluster."""
import argparse,hashlib,json,re,os,subprocess
from pathlib import Path
from urllib.parse import urlparse
from delivery import ROOT,SERVICES,SHA,validate_lock,write

def repo_url(value):
    u=urlparse(value)
    if u.scheme!='https' or u.hostname!='github.com' or u.username or u.password or u.query or u.fragment or not re.fullmatch(r'/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\.git)?',u.path):raise ValueError('Credential-free HTTPS GitHub repository URL required')
    return value
def application(name,repo,git_revision,release_id,environment='local'):
    if name not in {s['name'] for s in SERVICES} or not SHA.fullmatch(git_revision):raise ValueError('Fixed service and exact GitOps commit required')
    if environment!='local':raise ValueError('Production requires a separately reviewed environment overlay')
    stem=name.removesuffix('-service')
    return dict(apiVersion='argoproj.io/v1alpha1',kind='Application',metadata=dict(name=name,namespace='brain-delivery',annotations={'brain.knowledge/release-id':release_id}),spec=dict(project='knowledge-brain-local',source=dict(repoURL=repo_url(repo),targetRevision=git_revision,path='infra/charts/brain-service',helm=dict(releaseName=name,valueFiles=['../../services/'+stem+'-values.yaml','../../environments/local/'+stem+'-values.yaml','../../environments/local/observed-service-values.yaml','../../gitops/releases/local/'+release_id+'/'+name+'.json'])),destination=dict(server='https://kubernetes.default.svc',namespace='brain-apps'),syncPolicy=dict(syncOptions=['FailOnSharedResource=true','ApplyOutOfSyncOnly=true'])) )
def prepare(lock,repo,git_revision,output,previous='ABSENT'):
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    guard=output/'.promotion.lock'
    fd=os.open(guard,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    try:return prepare_locked(lock,repo,git_revision,output,previous)
    finally:os.close(fd);guard.unlink()

def prepare_locked(lock,repo,git_revision,output,previous='ABSENT'):
    validate_lock(lock);repo_url(repo)
    if not SHA.fullmatch(git_revision):raise ValueError('Exact GitOps configuration commit required')
    output=Path(output);pointer=output/'environments/local/release.json'
    if pointer.exists():
        if hashlib.sha256(pointer.read_bytes()).hexdigest()!=previous:raise ValueError('Environment pointer changed; inspect current release before promotion')
    elif previous!='ABSENT':raise ValueError('Expected prior environment pointer is missing')
    release_id=stage_release(lock,output)
    project=dict(apiVersion='argoproj.io/v1alpha1',kind='AppProject',metadata=dict(name='knowledge-brain-local',namespace='brain-delivery'),spec=dict(sourceRepos=[repo],destinations=[dict(server='https://kubernetes.default.svc',namespace='brain-apps')],clusterResourceWhitelist=[],namespaceResourceWhitelist=[dict(group='apps',kind='Deployment'),dict(group='',kind='Service'),dict(group='networking.k8s.io',kind='NetworkPolicy')]))
    write(output/'applications.json',dict(apiVersion='v1',kind='List',items=[project]+[application(s['name'],repo,git_revision,release_id) for s in SERVICES]))
    pointer.parent.mkdir(parents=True,exist_ok=True);pending=pointer.with_suffix('.pending');write(pending,dict(releaseId=release_id,sourceRevision=lock['sourceRevision'],gitopsRevision=git_revision));os.replace(pending,pointer)
    return release_id

def stage_release(lock,output):
    validate_lock(lock);output=Path(output)
    canonical=json.dumps(lock,sort_keys=True,separators=(',',':')).encode();release_id=hashlib.sha256(canonical).hexdigest();directory=output/'releases/local'/release_id;directory.mkdir(parents=True,exist_ok=True)
    for service in SERVICES:
        name=service['name'];image=lock['images'][name]
        value=dict(image=dict(repository=image['repository'],tag='',digest=image['digest']),podAnnotations={'brain.knowledge/source-revision':lock['sourceRevision'],'brain.knowledge/release-id':release_id},resourceAnnotations={'argocd.argoproj.io/sync-options':'Prune=false,Delete=false'},imagePullSecrets=[dict(name='brain-ghcr-pull')])
        target=directory/(name+'.json');encoded=(json.dumps(value,indent=2)+'\n').encode()
        if target.exists() and target.read_bytes()!=encoded:raise ValueError('Immutable release directory changed')
        target.write_bytes(encoded)
    target=directory/'release-lock.json';encoded=(json.dumps(lock,sort_keys=True,indent=2)+'\n').encode()
    if target.exists() and target.read_bytes()!=encoded:raise ValueError('Immutable release lock changed')
    target.write_bytes(encoded)
    return release_id

def verify_config_tree(output,release_id,revision,checkout=ROOT):
    checkout=Path(checkout).resolve();directory=(Path(output)/'releases/local'/release_id).resolve()
    if not SHA.fullmatch(revision) or not directory.is_relative_to(checkout):raise ValueError('Configuration must live inside the inspected Git checkout')
    for name in [s['name']+'.json' for s in SERVICES]+['release-lock.json']:
        file=directory/name
        relative=file.relative_to(checkout).as_posix()
        result=subprocess.run(['git','-C',str(checkout),'show',revision+':'+relative],check=True,capture_output=True,text=True,encoding='utf-8')
        if result.stdout!=file.read_text(encoding='utf-8'):raise ValueError('GitOps commit does not contain the staged release: '+relative)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--release',required=True);p.add_argument('--repository-url');p.add_argument('--gitops-revision');p.add_argument('--output',default=str(ROOT/'infra/gitops'));p.add_argument('--git-checkout',default=str(ROOT));p.add_argument('--stage-only',action='store_true');p.add_argument('--previous-sha256',default='ABSENT');a=p.parse_args();lock=json.loads(Path(a.release).read_text());rid=stage_release(lock,a.output)
    if a.stage_only:print(rid)
    else:
        if not a.repository_url or not a.gitops_revision:p.error('--repository-url and --gitops-revision are required for promotion')
        verify_config_tree(a.output,rid,a.gitops_revision,a.git_checkout)
        print(prepare(lock,a.repository_url,a.gitops_revision,a.output,a.previous_sha256))
