"""Confined fixed-index operator; --reset touches only the disposable public index."""
import json,time,subprocess,shutil,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 r=subprocess.run(k+args,input=body,text=True,encoding='utf-8',capture_output=True)
 if r.returncode:raise RuntimeError('Fixed public index operator failed')
 return r.stdout
assert run(['config','current-context']).strip()=='k3d-knowledge-brain'
meta=json.loads((root/'infra/dependencies/opensearch.json').read_text(encoding='utf-8'));name='public-search-initialize-'+str(int(time.time()));mapping=json.loads((root/'services/brain-query-service/src/main/resources/search/public-knowledge-v1.json').read_text());mapping['settings']={'number_of_shards':1,'number_of_replicas':0}
run(['apply','-f','-'],json.dumps({'apiVersion':'v1','kind':'ConfigMap','metadata':{'name':name,'namespace':'brain-data'},'data':{'mapping.json':json.dumps(mapping)}}))
script='''set -e
export OPENSEARCH_JAVA_HOME=/usr/share/opensearch/jdk
for attempt in $(seq 1 20); do
 if /usr/share/opensearch/plugins/opensearch-security/tools/securityadmin.sh -cd /security/ -h brain-opensearch.brain-data.svc -p 9200 -cn knowledge-brain-search -cacert /auth/ca.crt -cert /auth/tls.crt -key /auth/tls.key >/tmp/security.log 2>&1; then break; fi
 if [ "$attempt" = 20 ]; then cat /tmp/security.log; exit 1; fi
 sleep 3
done
'''
if '--reset' in sys.argv:script+="delete_status=$(curl --silent --output /tmp/delete.json --write-out '%{http_code}' --cacert /auth/ca.crt --cert /auth/tls.crt --key /auth/tls.key -X DELETE https://brain-opensearch.brain-data.svc:9200/brain-public-knowledge-v1)\nif [ \"$delete_status\" != 200 ] && [ \"$delete_status\" != 404 ]; then exit 1; fi\n"
script+='''status=$(curl --silent --output /tmp/index.json --write-out '%{http_code}' --cacert /auth/ca.crt --cert /auth/tls.crt --key /auth/tls.key https://brain-opensearch.brain-data.svc:9200/brain-public-knowledge-v1)
if [ "$status" = 404 ]; then curl --silent --fail --cacert /auth/ca.crt --cert /auth/tls.crt --key /auth/tls.key -H 'Content-Type: application/json' -X PUT --data-binary @/source/mapping.json https://brain-opensearch.brain-data.svc:9200/brain-public-knowledge-v1 >/dev/null; elif [ "$status" != 200 ]; then exit 1; fi
echo 'PASS fixed public search index and scoped reader/projector roles'
'''
job={'apiVersion':'batch/v1','kind':'Job','metadata':{'name':name,'namespace':'brain-data'},'spec':{'backoffLimit':0,'activeDeadlineSeconds':300,'ttlSecondsAfterFinished':86400,'template':{'metadata':{'labels':{'brain-search-operator':'true'}},'spec':{'restartPolicy':'Never','automountServiceAccountToken':False,'securityContext':{'runAsNonRoot':True,'runAsUser':1000,'fsGroup':1000,'seccompProfile':{'type':'RuntimeDefault'}},'containers':[{'name':'initialize','image':meta['image'],'command':['bash','-ec',script],'securityContext':{'allowPrivilegeEscalation':False,'readOnlyRootFilesystem':True,'capabilities':{'drop':['ALL']}},'resources':{'requests':{'cpu':'100m','memory':'256Mi'},'limits':{'cpu':'1','memory':'768Mi'}},'volumeMounts':[{'name':'auth','mountPath':'/auth','readOnly':True},{'name':'security','mountPath':'/security','readOnly':True},{'name':'source','mountPath':'/source','readOnly':True},{'name':'temp','mountPath':'/tmp'}]}],'volumes':[{'name':'auth','secret':{'secretName':'brain-search-admin-tls','defaultMode':288}},{'name':'security','configMap':{'name':'brain-opensearch-security'}},{'name':'source','configMap':{'name':name}},{'name':'temp','emptyDir':{'sizeLimit':'64Mi'}}]}}}}
run(['apply','-f','-'],json.dumps(job));uid=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))['metadata']['uid'];run(['patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':uid}]}})])
print('INDEX_JOB='+name,flush=True)
for attempt in range(120):
 state=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))['status']
 if state.get('succeeded'):break
 if state.get('failed'):raise RuntimeError('Public index operator failed: '+name)
 time.sleep(1)
else:raise RuntimeError('Public index operator deadline: '+name)
print('PASS public index operator JOB='+name)
