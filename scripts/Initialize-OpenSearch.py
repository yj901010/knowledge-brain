"""Restricted local operator Job: initialize security and the fixed note index."""
import argparse,json,shutil,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if p.returncode:raise RuntimeError(p.stderr)
 return p.stdout
assert run(['config','current-context']).strip()=='k3d-knowledge-brain','Local context required'
meta=json.loads((root/'infra/dependencies/opensearch.json').read_text(encoding='utf-8'))
name='opensearch-initialize-'+str(int(time.time()))
mapping=json.loads((root/'services/brain-query-service/src/main/resources/search/notes-v1.json').read_text(encoding='utf-8'));mapping['settings']={'number_of_shards':1,'number_of_replicas':0}
run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-data'),data={'mapping.json':json.dumps(mapping)})))
script="""for i in $(seq 1 90); do curl --silent --cacert /auth/ca.crt --cert /auth/tls.crt --key /auth/tls.key https://brain-opensearch.brain-data.svc:9200/ >/dev/null && break; sleep 2; done
export OPENSEARCH_JAVA_HOME=/usr/share/opensearch/jdk
/usr/share/opensearch/plugins/opensearch-security/tools/securityadmin.sh -cd /security/ -h brain-opensearch.brain-data.svc -p 9200 -cn knowledge-brain-search -cacert /auth/ca.crt -cert /auth/tls.crt -key /auth/tls.key
status=$(curl --silent --output /tmp/index.json --write-out '%{http_code}' --cacert /auth/ca.crt --cert /auth/tls.crt --key /auth/tls.key https://brain-opensearch.brain-data.svc:9200/brain-notes-v1)
if [ "$status" = 404 ]; then curl --silent --fail --cacert /auth/ca.crt --cert /auth/tls.crt --key /auth/tls.key -H 'Content-Type: application/json' -X PUT --data-binary @/source/mapping.json https://brain-opensearch.brain-data.svc:9200/brain-notes-v1 >/dev/null; elif [ "$status" != 200 ]; then echo 'Index initialization failed'; exit 1; fi
echo 'PASS security bootstrap and fixed note index'
"""
job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=300,ttlSecondsAfterFinished=86400,template=dict(metadata=dict(labels={'brain-search-operator':'true'}),spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=1000,fsGroup=1000,seccompProfile=dict(type='RuntimeDefault')),containers=[dict(name='initialize',image=meta['image'],command=['bash','-ec',script],securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='100m',memory='256Mi'),limits=dict(cpu='1',memory='768Mi')),volumeMounts=[dict(name='auth',mountPath='/auth',readOnly=True),dict(name='security',mountPath='/security',readOnly=True),dict(name='source',mountPath='/source',readOnly=True),dict(name='temp',mountPath='/tmp')])],volumes=[dict(name='auth',secret=dict(secretName='brain-search-admin-tls',defaultMode=288)),dict(name='security',configMap=dict(name='brain-opensearch-security')),dict(name='source',configMap=dict(name=name)),dict(name='temp',emptyDir=dict(sizeLimit='64Mi'))]))))
run(['apply','-f','-'],json.dumps(job));uid=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))['metadata']['uid'];run(['patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':uid}]}})])
print('JOB='+name,flush=True)
