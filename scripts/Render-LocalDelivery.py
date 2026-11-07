"""Render pinned, namespace-scoped Argo CD. Does not contact Kubernetes."""
import hashlib,json,sys
from pathlib import Path
import yaml
ROOT=Path(__file__).resolve().parents[1]
NS='brain-delivery';TARGET='brain-delivery-probe'
def render():
    lock=json.loads((ROOT/'infra/dependencies/delivery-tools.json').read_text())
    source=ROOT/'infra/dependencies/delivery/argocd-namespace-install.yaml'
    if hashlib.sha256(source.read_bytes()).hexdigest()!=lock['argocd']['manifestSha256']:raise ValueError('Upstream Argo manifest changed')
    docs=[dict(apiVersion='v1',kind='Namespace',metadata=dict(name=n)) for n in [NS,TARGET]]
    profiles={'argocd-application-controller':('200m','384Mi','1','768Mi'),'argocd-repo-server':('100m','192Mi','1','512Mi'),'argocd-server':('50m','96Mi','500m','256Mi'),'argocd-redis':('25m','48Mi','250m','128Mi')}
    for doc in yaml.safe_load_all(source.read_text()):
        if not doc:continue
        if doc['kind'].startswith('Cluster'):raise ValueError('Cluster-wide role forbidden')
        doc['metadata']['namespace']=NS
        for subject in doc.get('subjects',[]):
            if subject.get('namespace')=='argocd':subject['namespace']=NS
        name=doc['metadata']['name']
        if doc['kind'] in ['Deployment','StatefulSet']:
            spec=doc['spec'];profile=profiles.get(name);spec['replicas']=1 if profile else 0
            pod=spec['template']['spec']
            for c in pod.get('containers',[])+pod.get('initContainers',[]):
                if 'argocd' in c['image']:c['image']=lock['images']['argocd']
                elif c['name']=='redis':c['image']=lock['images']['redis']
                if profile:
                    cpu,memory,limit,memlimit=profile;c['resources']=dict(requests=dict(cpu=cpu,memory=memory),limits=dict(cpu=limit,memory=memlimit))
        if name=='argocd-rbac-cm':doc['data']={'policy.default':'role:readonly','policy.csv':'','scopes':'[groups]'}
        if name=='argocd-cm':doc['data']={'admin.enabled':'true','exec.enabled':'false','application.resourceTrackingMethod':'annotation'}
        if name=='argocd-server-network-policy':
            doc['spec']['ingress']=[{'from':[{'podSelector':{}}]}]
        docs.append(doc)
    docs += [dict(apiVersion='v1',kind='Secret',metadata=dict(name='local-probe-cluster',namespace=NS,labels={'argocd.argoproj.io/secret-type':'cluster'}),stringData=dict(name='local-probe',server='https://kubernetes.default.svc',namespaces=TARGET,clusterResources='false',config='{}')),
      dict(apiVersion='rbac.authorization.k8s.io/v1',kind='Role',metadata=dict(name='delivery-probe',namespace=TARGET),rules=[dict(apiGroups=['*'],resources=['*'],verbs=['get','list','watch']),dict(apiGroups=['apps'],resources=['deployments'],verbs=['create','update','patch']),dict(apiGroups=[''],resources=['services','events'],verbs=['create','update','patch']),dict(apiGroups=['networking.k8s.io'],resources=['networkpolicies'],verbs=['create','update','patch'])]),
      dict(apiVersion='rbac.authorization.k8s.io/v1',kind='RoleBinding',metadata=dict(name='delivery-probe',namespace=TARGET),roleRef=dict(apiGroup='rbac.authorization.k8s.io',kind='Role',name='delivery-probe'),subjects=[dict(kind='ServiceAccount',name='argocd-application-controller',namespace=NS)])]
    return docs
if __name__=='__main__':
    target=Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'.local/delivery/argocd.yaml';target.parent.mkdir(parents=True,exist_ok=True);target.write_text(yaml.safe_dump_all(render(),sort_keys=False));print(target)
