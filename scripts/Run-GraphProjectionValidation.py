"""Launch real browser-to-graph validation with scoped Cassandra credentials, never graph admin."""
import argparse,json,re,shutil,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--failures',action='store_true');p.add_argument('--rebuild',action='store_true');a=p.parse_args()
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if p.returncode:raise RuntimeError('Local graph runtime verification operation failed')
 return p.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
deploy=json.loads(run(['get','deployment','brain-query-service','-n','brain-apps','-o','json']));image=deploy['spec']['template']['spec']['containers'][0]['image']
if not re.fullmatch(r'knowledge-brain/brain-query-service:local-[0-9a-f]{16}',image):raise RuntimeError('Expected local imported Query image')
name='graph-projection-validation-'+str(int(time.time()))
logging={'logback.xml':'<configuration><appender name="console" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger{36} - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="console"/></root></configuration>'}
run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-data'),data=logging)))
volumes=[dict(name='source',configMap=dict(name=name)),dict(name='tmp',emptyDir=dict(sizeLimit='32Mi')),dict(name='public-trust',secret=dict(secretName='brain-guest-api-trust')),dict(name='graph-projector',secret=dict(secretName='brain-graph-projector'))]
mounts=[dict(name='source',mountPath='/source',readOnly=True),dict(name='tmp',mountPath='/tmp'),dict(name='public-trust',mountPath='/public-trust',readOnly=True),dict(name='graph-projector',mountPath='/graph-projector',readOnly=True)]
job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=2400,ttlSecondsAfterFinished=86400,template=dict(metadata=dict(labels={'brain-graph-client':'true','brain-guest-api-validation':'true'}),spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,fsGroup=10001,seccompProfile=dict(type='RuntimeDefault')),containers=[dict(name='verify',image=image,command=['java','-Dlogback.configurationFile=/source/logback.xml','-cp','/app/resources:/app/classes:/app/libs/*','io.knowledgebrain.brainquery.GraphRuntimeValidation'],env=[dict(name='BRAIN_GRAPH_VALIDATION_FAILURES',value=str(a.failures).lower()),dict(name='BRAIN_GRAPH_VALIDATION_REBUILD',value=str(a.rebuild).lower())],securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='100m',memory='256Mi'),limits=dict(cpu='1',memory='768Mi')),volumeMounts=mounts)],volumes=volumes))))
run(['apply','-f','-'],json.dumps(job));uid=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))['metadata']['uid'];run(['patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':uid}]}})])
print('JOB='+name,flush=True)
