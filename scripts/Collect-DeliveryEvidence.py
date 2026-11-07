"""Collect bounded local delivery evidence; no domain writes or credentials exported."""
import collections,hashlib,json,re,runpy,subprocess,urllib.parse,base64,io,tarfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    helper=runpy.run_path(str(ROOT/'scripts/Run-DeliveryDrill.py'));run=helper['run'];get=helper['get'];baseline=json.loads((ROOT/'.local/delivery/baseline.json').read_text());job=json.loads((ROOT/'.local/delivery/ci-job.json').read_text())
    if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise ValueError('Local context required')
    def items(kind,ns=None):return json.loads(run(['get',kind]+(['-n',ns] if ns else ['-A'])+['-o','json']))['items']
    if get('namespace','kube-system','default')['metadata']['uid']!=baseline['clusterUid']:raise ValueError('Cluster changed')
    apps=items('deployment','brain-apps');monitoring=items('deployment','brain-observability');pvcs=items('pvc')
    def stable(old,new):
        old={d['metadata']['name']:d for d in old};new={d['metadata']['name']:d for d in new}
        assert set(old)==set(new)
        for name in old:assert old[name]['metadata']['uid']==new[name]['metadata']['uid'] and old[name]['spec']==new[name]['spec'],name+' changed'
        assert all(d.get('status',{}).get('readyReplicas',0)==d['spec']['replicas'] for d in new.values())
    stable(baseline['apps']['items'],apps);stable(baseline['monitoring']['items'],monitoring)
    def pvc_ids(rows):return {(p['metadata']['namespace'],p['metadata']['name']):p['metadata']['uid'] for p in rows}
    assert pvc_ids(baseline['pvcs']['items'])==pvc_ids(pvcs)
    assert get('job',job['name'],job['namespace']).get('status',{}).get('succeeded')==1
    logs=run(['logs','job/'+job['name'],'-n',job['namespace'],'-c','verify']);(ROOT/'.local/delivery/backend.log').write_text(logs,encoding='utf-8')
    summaries={};module=None
    for line in logs.splitlines():
        match=re.search(r'--- surefire:[^@]+@ ([a-z-]+) ---',line)
        if match:module=match.group(1)
        match=re.fullmatch(r'\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)',line)
        if match:
            total,failures,errors,skipped=map(int,match.groups());summaries[module]=dict(executed=total-skipped,skipped=skipped,failures=failures,errors=errors)
    expected={s['name'] for s in json.loads((ROOT/'scripts/ci/services.json').read_text())};assert expected<=set(summaries)
    assert all(s['executed']>0 and s['failures']==0 and s['errors']==0 for s in summaries.values())
    maps=json.loads(run(['get','configmap',*job['sourceMaps'],'-n',job['namespace'],'-o','json']))['items'];parts={}
    for cm in maps:
        for name,value in cm['data'].items():
            if name.startswith('part'):parts[name]=value
    archive=base64.b64decode(''.join(parts[n] for n in sorted(parts)));verified=0
    with tarfile.open(fileobj=io.BytesIO(archive),mode='r:gz') as tar:
        for member in tar:
            path=Path(member.name)
            if member.isfile() and (path.parts[0] in ['services','contracts','libraries'] or member.name=='pom.xml'):
                assert not path.is_absolute() and '..' not in path.parts
                assert tar.extractfile(member).read()==(ROOT/path).read_bytes(),str(path)+' source changed since backend validation';verified+=1
    def metric(expression):
        url='http://127.0.0.1:9090/api/v1/query?'+urllib.parse.urlencode(dict(query=expression))
        result=json.loads(run(['exec','-n','brain-observability','deployment/brain-prometheus','--','wget','-qO-',url]));return result['data']['result']
    jvms=metric('count(count by(service_name)(jvm_memory_used_bytes))');cdc=metric('sum(brain_cdc_connector_running)')
    assert float(jvms[0]['value'][1])==13;assert float(cdc[0]['value'][1])==10
    argo=get('application','delivery-probe');assert argo['status']['sync']['status']=='Synced' and argo['status']['health']['status']=='Healthy'
    result=dict(clusterUid=baseline['clusterUid'],appsReady=len(apps),monitoringReady=len(monitoring),pvcIdentitiesPreserved=len(pvcs),deploymentSpecsAndImagesPreserved=True,jvmServices=13,connectorsRunning=10,backend=dict(services=summaries,executed=sum(s['executed'] for s in summaries.values()),skipped=sum(s['skipped'] for s in summaries.values()),sourceFilesCompared=verified,archiveSha256=hashlib.sha256(archive).hexdigest(),job=job['name'],liveKafkaConfigured=False),argo=dict(sync='Synced',health='Healthy',revision=argo['status']['sync']['revision']))
    (ROOT/'.local/delivery/evidence.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
    report=ROOT/'.local/delivery/gateway-trivy.json';scan=json.loads(report.read_text());findings=[]
    for target in scan.get('Results',[]):
        for v in target.get('Vulnerabilities',[]):findings.append(dict(target=target['Target'],id=v['VulnerabilityID'],package=v['PkgName'],installed=v['InstalledVersion'],fixedAccordingToScanner=v.get('FixedVersion',''),severity=v['Severity'],advisory=v.get('PrimaryURL','')))
    scan_result=dict(checkedAt=scan.get('CreatedAt'),scanner='Trivy 0.76.2',image=json.loads((ROOT/'.local/delivery/drill.json').read_text())['image'],reportSha256=hashlib.sha256(report.read_bytes()).hexdigest(),releaseGate='BLOCKED',counts=dict(collections.Counter(v['severity'] for v in findings)),findings=findings,exploitabilityValidated=False)
    (ROOT/'docs/delivery-scan-findings.json').write_text(json.dumps(scan_result,indent=2)+'\n')
if __name__=='__main__':main()
