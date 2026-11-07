"""Trusted local HTTPS hot/cold/private/revocation checks; optional cache-only outage drill.

Credentials remain in memory. No public writes, role grants, DB resets or PVC changes.
"""
import argparse, concurrent.futures, hashlib, http.cookiejar, json, ssl, time
import urllib.request, urllib.error, urllib.parse
from local_recovery import Cluster, ROOT, now, write_json

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--fault',action='store_true');args=parser.parse_args()
    c=Cluster(ROOT/'.local/kubeconfig.yaml','knowledge-brain');uid=c.uid()
    tls=ssl.create_default_context(cafile=str(ROOT/'.local/guest-api-ca.crt'));origin='https://localhost:8443'
    samples=[]
    def browser():
        jar=http.cookiejar.CookieJar()
        return jar,urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar),urllib.request.HTTPSHandler(context=tls))
    def call(opener,path,method='GET',body=None,cookie=None):
        headers={'Origin':origin,'Sec-Fetch-Site':'same-origin','Content-Type':'application/json'}
        if cookie:headers['Cookie']=cookie
        req=urllib.request.Request(origin+path,method=method,headers=headers,
                data=None if body is None else json.dumps(body).encode())
        started=time.monotonic()
        try:
            with opener.open(req,timeout=20) as r:status,head,wire=r.status,dict(r.headers),r.read(524289)
        except urllib.error.HTTPError as e:status,head,wire=e.code,dict(e.headers),e.read(524289)
        assert len(wire)<=524288
        return status,head,json.loads(wire) if wire else None,1000*(time.monotonic()-started)
    def expect(result,status):
        assert result[0]==status,('Expected',status,'got',result[0])
        if status in [200,201,204]:assert 'no-store' in result[1].get('Cache-Control','')
        return result[2]
    jar,a=browser();expect(call(a,'/api/identity/guest-session','POST',{}),201)
    _,b=browser();expect(call(b,'/api/identity/guest-session','POST',{}),201)
    catalog=expect(call(a,'/api/spaces/public'),200)['items']
    space=next(v['spaceId'] for v in catalog if v['domain']=='DEVELOPMENT');base='/api/public/spaces/'+space
    seed=expect(call(a,base+'/search?q=Discovery%20subject&limit=1'),200)
    assert seed['items'],'Existing reviewed public development fixture required'
    row=seed['items'][0];path=base+'/search?q='+urllib.parse.quote(row['title'])+'&limit=1'
    expected=None
    for _ in range(8):
        result=call(a,path);page=expect(result,200);assert page['items'] and page['items'][0]['entityId']==row['entityId']
        digest=hashlib.sha256(json.dumps(page,sort_keys=True).encode()).hexdigest()
        if expected is None:expected=digest
        assert digest==expected;samples.append(result[3])
    old_cookie='; '.join(v.name+'='+v.value for v in jar)
    def parallel(_):
        _,client=browser();result=call(client,path,cookie=old_cookie)
        assert expect(result,200)['items'][0]['entityId']==row['entityId'];return result[3]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:samples.extend(pool.map(parallel,range(12)))
    # A private canonical Note must still reach its private projection but never the shared cache.
    for _ in range(45):
        result=call(a,'/api/spaces/personal')
        if result[0]==200:personal=expect(result,200)['spaceId'];break
        assert result[0]==202;time.sleep(1)
    else:raise TimeoutError('Guest Space provisioning')
    marker='CachePrivate'+str(time.time_ns());note=expect(call(a,'/api/spaces/'+personal+'/notes','POST',
        {'title':marker,'markdown':'Private cache-isolation fixture'}),201)
    note_path='/api/spaces/'+personal+'/notes/'+note['noteId']
    expect(call(b,note_path),404)
    for _ in range(60):
        private=expect(call(a,'/api/spaces/'+personal+'/search/network?q='+marker),200)
        if any(v['id']==note['noteId'] for v in private['items']):break
        time.sleep(1)
    else:raise TimeoutError('Private Note Outbox/CDC projection')
    for _ in range(2):assert expect(call(a,base+'/search?q='+marker),200)['items']==[]
    expect(call(a,'/api/public/spaces/'+personal+'/search?q='+marker),404)
    before=expect(call(a,note_path),200);cache=c.get('deployment','brain-valkey','brain-data');replicas=cache['spec']['replicas']
    assert replicas==1;outage=[]
    if args.fault:
        try:
            assert c.uid()==uid
            c.run('scale','deployment/brain-valkey','-n','brain-data','--replicas=0')
            c.run('rollout','status','deployment/brain-valkey','-n','brain-data','--timeout=60s',timeout=70)
            # Expire the local short cache and exercise unavailable transport, not only an existing hit.
            time.sleep(1.2)
            for _ in range(8):
                result=call(a,path);assert expect(result,200)['items'][0]['entityId']==row['entityId'];outage.append(result[3])
            assert c.get('deployment','brain-query-service','brain-apps')['status']['readyReplicas']==1
            assert expect(call(a,note_path),200)==before
        finally:
            assert c.uid()==uid
            c.run('scale','deployment/brain-valkey','-n','brain-data','--replicas='+str(replicas))
            c.run('rollout','status','deployment/brain-valkey','-n','brain-data','--timeout=90s',timeout=100)
        assert c.get('deployment','brain-valkey','brain-data')['metadata']['uid']==cache['metadata']['uid']
        time.sleep(1.2)
        for _ in range(8):assert expect(call(a,path),200)['items'][0]['entityId']==row['entityId']
    # Present the actual revoked credential explicitly, rather than merely omitting the cookie.
    expect(call(a,'/api/identity/guest-session/revoke','POST',{}),204)
    _,stale=browser();expect(call(stale,path,cookie=old_cookie),401)
    assert expect(call(b,path),200)['items'][0]['entityId']==row['entityId']
    proof=dict(clusterUid=uid,completedAt=now(),image=c.get('deployment','brain-query-service','brain-apps')['spec']['template']['spec']['containers'][0]['image'],
        publicResponseSha256=expected,privateResponseSha256=hashlib.sha256(json.dumps(before,sort_keys=True).encode()).hexdigest(),
        hotColdRequests=len(samples),hotColdMilliseconds=samples,outageRequests=len(outage),outageMilliseconds=outage,
        checks=['fresh canonical public rows','bounded concurrent reads','private Note CDC projection','public/private separation','foreign Note denial',
                'cache-only outage fallback and recovery' if args.fault else 'no outage requested','Query readiness','same private canonical response','immediate revoked-credential denial','independent other guest'])
    write_json(ROOT/'.local/valkey-api-proof.json',proof)
    print('PASS '+str(len(samples))+' public reads, private CDC/isolation, revoked credential, '+str(len(outage))+' cache-outage reads and recovery; no credentials exported')

if __name__=='__main__':main()
