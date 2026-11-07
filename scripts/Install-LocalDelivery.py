"""Install the pinned namespace-scoped local controller, without adopting existing apps."""
import hashlib,json,subprocess,runpy
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    helper=runpy.run_path(str(ROOT/'scripts/Run-DeliveryDrill.py'));run=helper['run']
    if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise ValueError('Local context required')
    lock=json.loads((ROOT/'infra/dependencies/delivery-tools.json').read_text())
    for item in lock['argocd']['crds']:
        source=ROOT/item['file']
        if hashlib.sha256(source.read_bytes()).hexdigest()!=item['sha256']:raise ValueError('Pinned CRD checksum mismatch')
        run(['apply','--server-side','-f',str(source)])
    for item in ['applications','appprojects','applicationsets']:run(['wait','--for=condition=Established','crd/'+item+'.argoproj.io','--timeout=60s'])
    renderer=runpy.run_path(str(ROOT/'scripts/Render-LocalDelivery.py'));documents=renderer['render']()
    print(helper['apply'](dict(apiVersion='v1',kind='List',items=documents)))
    for name in ['argocd-redis','argocd-repo-server','argocd-server']:print(run(['rollout','status','deployment/'+name,'-n','brain-delivery','--timeout=240s']))
    print(run(['rollout','status','statefulset/argocd-application-controller','-n','brain-delivery','--timeout=240s']))
if __name__=='__main__':main()
