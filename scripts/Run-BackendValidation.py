import base64,io,json,subprocess,tarfile,time,shutil,sys,uuid
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 r=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if r.returncode: raise RuntimeError(r.stderr)
 return r.stdout
if run(['config','current-context']).strip() != 'k3d-knowledge-brain':
 raise RuntimeError('This integration runner is restricted to the local knowledge-brain context')
cdc='--cdc' in sys.argv
archive=io.BytesIO()
with tarfile.open(fileobj=archive,mode='w:gz') as tar:
 for p in [root/'pom.xml']+[p for directory in ('services','contracts','libraries') for p in (root/directory).rglob('*')]:
  if p.is_file() and 'target' not in p.relative_to(root).parts and (p.name=='pom.xml' or 'src' in p.relative_to(root).parts):tar.add(p,arcname=str(p.relative_to(root)).replace('\\','/'))
name='backend-validation-'+str(time.time_ns())+'-'+uuid.uuid4().hex[:8]
encoded=base64.b64encode(archive.getvalue()).decode();source_maps=[]
for ordinal,offset in enumerate(range(0,len(encoded),700000)):
 source_name=name+'-source-'+str(ordinal).zfill(3);source_maps.append(source_name)
 cm=dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=source_name,namespace='brain-data'),data={'source.part'+str(ordinal).zfill(3)+'.b64':encoded[offset:offset+700000]})
 run(['apply','--server-side','-f','-'],json.dumps(cm))
job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=600,ttlSecondsAfterFinished=86400,template=dict(spec=dict(restartPolicy='Never',automountServiceAccountToken=False,containers=[dict(name='verify',image='maven:3.10.0-eclipse-temurin-25@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525',command=['bash','-ec','set -o pipefail; mkdir -p /tmp/build; cat /source/source.part*.b64 | base64 -d | tar xz -C /tmp/build; cd /tmp/build; mvn -B -Dstyle.color=never -Dmaven.repo.local=/tmp/m2 verify'],env=[dict(name='BRAIN_TEST_DATABASE_URL',value='jdbc:postgresql://brain-postgres.brain-data.svc:5432/brain_identity'),dict(name='BRAIN_TEST_DATABASE_PASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-identity-db',key='password')))],resources=dict(requests=dict(cpu='500m',memory='1Gi'),limits=dict(cpu='2',memory='2Gi')),volumeMounts=[dict(name='source',mountPath='/source',readOnly=True)])],volumes=[dict(name='source',projected=dict(sources=[dict(configMap=dict(name=source_name)) for source_name in source_maps]))]))))
container=job['spec']['template']['spec']['containers'][0]
container['env'] += [dict(name='BRAIN_TEST_SPACE_DATABASE_URL',value='jdbc:postgresql://brain-postgres.brain-data.svc:5432/brain_space'),dict(name='BRAIN_TEST_SPACE_DATABASE_PASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-space-db',key='password')))]
container['env'] += [dict(name='BRAIN_TEST_CONTENT_DATABASE_URL',value='jdbc:postgresql://brain-postgres.brain-data.svc:5432/brain_content'),dict(name='BRAIN_TEST_CONTENT_DATABASE_PASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-content-db',key='password')))]
container['env'] += [dict(name='BRAIN_TEST_MEDIA_DATABASE_URL',value='jdbc:postgresql://brain-postgres.brain-data.svc:5432/brain_media'),dict(name='BRAIN_TEST_MEDIA_DATABASE_PASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-media-db',key='password')))]
container['env'] += [dict(name='BRAIN_TEST_QUERY_DATABASE_URL',value='jdbc:postgresql://brain-postgres.brain-data.svc:5432/brain_brainquery'),dict(name='BRAIN_TEST_QUERY_DATABASE_PASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-brainquery-db',key='password')))]
container['env'] += [dict(name='BRAIN_TEST_KNOWLEDGE_DATABASE_URL',value='jdbc:postgresql://brain-postgres.brain-data.svc:5432/brain_knowledge'),dict(name='BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-knowledge-db',key='password')))]
container['env'] += [dict(name='BRAIN_TEST_LEARNING_DATABASE_URL',value='jdbc:postgresql://brain-postgres.brain-data.svc:5432/brain_learning'),dict(name='BRAIN_TEST_LEARNING_DATABASE_PASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-learning-db',key='password')))]
container['env'] += [dict(name='BRAIN_TEST_PLANNING_DATABASE_URL',value='jdbc:postgresql://brain-postgres.brain-data.svc:5432/brain_planning'),dict(name='BRAIN_TEST_PLANNING_DATABASE_PASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-planning-db',key='password')))]
container['env'] += [dict(name='BRAIN_TEST_NOTIFICATION_DATABASE_URL',value='jdbc:postgresql://brain-postgres.brain-data.svc:5432/brain_notification'),dict(name='BRAIN_TEST_NOTIFICATION_DATABASE_PASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-notification-db',key='password')))]
container['env'] += [dict(name='BRAIN_TEST_COLLABORATION_DATABASE_URL',value='jdbc:postgresql://brain-postgres.brain-data.svc:5432/brain_collaboration'),dict(name='BRAIN_TEST_COLLABORATION_DATABASE_PASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-collaboration-db',key='password')))]
if '--group-membership-kafka' in sys.argv:container['env'].append(dict(name='BRAIN_TEST_GROUP_MEMBERSHIP_KAFKA',value='brain-kafka-kafka-bootstrap.brain-events.svc:9093'))
if '--group-kafka' in sys.argv or '--group-membership-kafka' in sys.argv:
 container['env'].append(dict(name='BRAIN_TEST_GROUP_KAFKA_BOOTSTRAP',value='brain-kafka-kafka-bootstrap.brain-events.svc:9093'));container['volumeMounts'].append(dict(name='group-kafka-test',mountPath='/group-kafka-test',readOnly=True));job['spec']['template']['spec']['volumes'].append(dict(name='group-kafka-test',secret=dict(secretName='brain-group-validator')))
if '--collaboration' in sys.argv:container['command'][-1]+=' -pl services/collaboration-service -am'
if '--notification' in sys.argv:container['command'][-1]+=' -pl services/notification-service -am'
if '--notification-kafka' in sys.argv:
 container['env'].append(dict(name='BRAIN_TEST_NOTIFICATION_KAFKA_BOOTSTRAP',value='brain-kafka-kafka-bootstrap.brain-events.svc:9093'));container['volumeMounts'].append(dict(name='notification-kafka-test',mountPath='/notification-kafka-test',readOnly=True));job['spec']['template']['spec']['volumes'].append(dict(name='notification-kafka-test',secret=dict(secretName='brain-notification-validator')))
container['env'] += [dict(name='BRAIN_TEST_INTEGRATION_DATABASE_URL',value='jdbc:postgresql://brain-postgres.brain-data.svc:5432/brain_integration'),dict(name='BRAIN_TEST_INTEGRATION_DATABASE_PASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-integration-db',key='password')))]
if '--integration' in sys.argv:container['command'][-1]+=' -pl services/integration-service -am'
if '--integration-kafka' in sys.argv:
 container['env'].append(dict(name='BRAIN_TEST_INTEGRATION_KAFKA_BOOTSTRAP',value='brain-kafka-kafka-bootstrap.brain-events.svc:9093'));container['volumeMounts'].append(dict(name='integration-kafka-test',mountPath='/integration-kafka-test',readOnly=True));job['spec']['template']['spec']['volumes'].append(dict(name='integration-kafka-test',secret=dict(secretName='brain-integration-validator')))
if '--planning' in sys.argv:container['command'][-1]+=' -pl services/planning-service -am'
if '--planning-kafka' in sys.argv:
 container['env'].append(dict(name='BRAIN_TEST_PLANNING_KAFKA_BOOTSTRAP',value='brain-kafka-kafka-bootstrap.brain-events.svc:9093'));container['volumeMounts'].append(dict(name='planning-kafka-test',mountPath='/planning-kafka-test',readOnly=True));job['spec']['template']['spec']['volumes'].append(dict(name='planning-kafka-test',secret=dict(secretName='brain-planning-validator')))
if '--activity' in sys.argv:container['command'][-1]+=' -pl services/activity-service -am'
if '--learning' in sys.argv:container['command'][-1]+=' -pl services/learning-service -am'
if '--activity-storage' in sys.argv:
 job['spec']['template'].setdefault('metadata',{}).setdefault('labels',{})['brain-activity-validation']='true'
 container['env'] += [dict(name='BRAIN_TEST_ACTIVITY_DATABASE_URL',value='jdbc:postgresql://brain-postgres.brain-data.svc:5432/brain_activity'),dict(name='BRAIN_TEST_ACTIVITY_DATABASE_PASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-activity-db',key='password'))),dict(name='BRAIN_TEST_ACTIVITY_CLICKHOUSE',value='https://brain-clickhouse.brain-data.svc:8443'),dict(name='BRAIN_TEST_ACTIVITY_KAFKA',value='brain-kafka-kafka-bootstrap.brain-events.svc:9093')]
 for volume_name,resource,path in [('activity-reader','brain-activity-clickhouse-reader','/clickhouse-reader'),('activity-projector','brain-activity-clickhouse-projector','/clickhouse-projector'),('activity-trust','brain-guest-api-trust','/activity-trust'),('activity-kafka','brain-activity-kafka','/activity-kafka')]:
  container['volumeMounts'].append(dict(name=volume_name,mountPath=path,readOnly=True));job['spec']['template']['spec']['volumes'].append(dict(name=volume_name,secret=dict(secretName=resource)))
if '--learning-kafka' in sys.argv:
 container['env'].append(dict(name='BRAIN_TEST_LEARNING_KAFKA_BOOTSTRAP',value='brain-kafka-kafka-bootstrap.brain-events.svc:9093'));container['volumeMounts'].append(dict(name='learning-kafka-test',mountPath='/learning-kafka-test',readOnly=True));job['spec']['template']['spec']['volumes'].append(dict(name='learning-kafka-test',secret=dict(secretName='brain-learning-validator')))
if '--search-storage' in sys.argv:
 job['spec']['template'].setdefault('metadata',{}).setdefault('labels',{})['brain-search-client']='true'
 container['env'].append(dict(name='BRAIN_TEST_SEARCH_ORIGIN',value='https://brain-opensearch.brain-data.svc:9200'))
 for mode in ['reader','projector']:
  container['volumeMounts'].append(dict(name='search-'+mode,mountPath='/search-'+mode,readOnly=True))
  job['spec']['template']['spec']['volumes'].append(dict(name='search-'+mode,secret=dict(secretName='brain-search-'+mode+'-tls')))
if '--graph-storage' in sys.argv:
 job['spec']['template'].setdefault('metadata',{}).setdefault('labels',{})['brain-graph-client']='true'
 container['env'].append(dict(name='BRAIN_TEST_GRAPH_HOST',value='brain-cassandra.brain-data.svc'))
 for role in (['projector'] if '--graph-restart' in sys.argv else ['admin','projector']):
  container['volumeMounts'].append(dict(name='graph-'+role,mountPath='/graph-'+role,readOnly=True))
  job['spec']['template']['spec']['volumes'].append(dict(name='graph-'+role,secret=dict(secretName='brain-graph-'+role)))
 container['volumeMounts'].append(dict(name='graph-trust',mountPath='/graph-trust',readOnly=True))
 job['spec']['template']['spec']['volumes'].append(dict(name='graph-trust',emptyDir=dict(sizeLimit='1Mi')))
 job['spec']['template']['spec']['initContainers']=[dict(name='graph-trust',image=container['image'],command=['bash','-ec','keytool -importcert -noprompt -alias brain-ca -file /graph-projector/ca.crt -keystore /graph-trust/truststore.p12 -storetype PKCS12 -storepass:env TRUST_PASSWORD >/dev/null 2>&1'],env=[dict(name='TRUST_PASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-graph-projector',key='password')))],resources=dict(requests=dict(cpu='50m',memory='128Mi'),limits=dict(cpu='500m',memory='256Mi')),volumeMounts=[dict(name='graph-projector',mountPath='/graph-projector',readOnly=True),dict(name='graph-trust',mountPath='/graph-trust')])]
if '--graph-only' in sys.argv:
 container['command'][-1]+=' -Dtest=NoteGraphStoreIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false'
if '--graph-restart' in sys.argv:
 container['env'].append(dict(name='BRAIN_TEST_GRAPH_RESTART_VERIFY',value='true'))
 container['command'][-1]+=' -Dtest=NoteGraphStoreIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false'
if '--public-storage-only' in sys.argv:
 container['command'][-1]+=' -Dtest=PublicStorageIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false'
if '--brain-query' in sys.argv:container['command'][-1]+=' -pl services/brain-query-service -am'
if '--media' in sys.argv:container['command'][-1]+=' -pl services/media-service -am'
if '--media-storage' in sys.argv:
 job['spec']['template'].setdefault('metadata',{}).setdefault('labels',{})['media-storage-client']='true'
 for variable,key in [('BRAIN_TEST_STORAGE_ACCESS_KEY','accessKey'),('BRAIN_TEST_STORAGE_SECRET_KEY','secretKey')]:container['env'].append(dict(name=variable,valueFrom=dict(secretKeyRef=dict(name='brain-media-storage',key=key))))
 container['env'].append(dict(name='BRAIN_TEST_STORAGE_ORIGIN',value='https://brain-minio.brain-data.svc:9000'));container['volumeMounts'].append(dict(name='storage-trust',mountPath='/storage-trust',readOnly=True));job['spec']['template']['spec']['volumes'].append(dict(name='storage-trust',secret=dict(secretName='brain-guest-api-trust')))
if '--media-kafka' in sys.argv:
 container['env'].append(dict(name='BRAIN_TEST_MEDIA_KAFKA_BOOTSTRAP',value='brain-kafka-kafka-bootstrap.brain-events.svc:9093'));container['volumeMounts'].append(dict(name='media-kafka-test',mountPath='/media-kafka-test',readOnly=True));job['spec']['template']['spec']['volumes'].append(dict(name='media-kafka-test',secret=dict(secretName='brain-media-validator')))
if '--content' in sys.argv:
 container['command'][-1]+=' -pl services/content-service -am'
if '--knowledge' in sys.argv:
 container['command'][-1]+=' -pl services/knowledge-service -am'
if '--citations-only' in sys.argv:
 if '--knowledge' not in sys.argv:raise ValueError('Citation tests require the Knowledge service scope')
 container['command'][-1]+=' -Dtest=CitationApiTest,CitationSecurityBoundaryTest,CitationStoreIntegrationTest,CitationKafkaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false'
if '--source-citations-only' in sys.argv:
 if '--knowledge' not in sys.argv:raise ValueError('Source citation tests require the Knowledge service scope')
 container['command'][-1]+=' -Dtest=SourceCitationApiTest,SourceCitationSecurityBoundaryTest,SourceCitationStoreIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false'
if '--claim-usages-only' in sys.argv:
 if '--knowledge' not in sys.argv:raise ValueError('Claim usage tests require the Knowledge service scope')
 container['command'][-1]+=' -Dtest=ClaimUsageApiTest,ClaimUsageStoreIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false'
if '--content-kafka' in sys.argv:
 container['env'].append(dict(name='BRAIN_TEST_CONTENT_KAFKA_BOOTSTRAP',value='brain-kafka-kafka-bootstrap.brain-events.svc:9093'))
 container['volumeMounts'].append(dict(name='content-kafka-test',mountPath='/content-kafka-test',readOnly=True))
 job['spec']['template']['spec']['volumes'].append(dict(name='content-kafka-test',secret=dict(secretName='brain-content-validator')))
if '--public-records-only' in sys.argv:
 if '--knowledge' not in sys.argv:raise ValueError('Public record tests require Knowledge scope')
 container['command'][-1]+=' -Dtest=PublicRecordApiTest,PublicRecordStoreIntegrationTest,PublicRecordKafkaIntegrationTest,PublicRecordSecurityBoundaryTest,PublicDiscoveryApiIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false'
if '--public-security-only' in sys.argv:
 if '--knowledge' not in sys.argv:raise ValueError('Public security tests require Knowledge scope')
 container['command'][-1]+=' -Dtest=PublicRecordSecurityBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false'
if '--knowledge-kafka' in sys.argv:
 container['env'].append(dict(name='BRAIN_TEST_KNOWLEDGE_KAFKA_BOOTSTRAP',value='brain-kafka-kafka-bootstrap.brain-events.svc:9093'));container['volumeMounts'].append(dict(name='knowledge-kafka-test',mountPath='/knowledge-kafka-test',readOnly=True));job['spec']['template']['spec']['volumes'].append(dict(name='knowledge-kafka-test',secret=dict(secretName='brain-knowledge-validator')))
if '--space' in sys.argv:
 container['command'][-1]+=' -pl services/space-service -am'
if '--public-reviewer-id' in sys.argv:
 import uuid
 value=sys.argv[sys.argv.index('--public-reviewer-id')+1]
 if uuid.UUID(value).version!=7 or str(uuid.UUID(value))!=value:raise ValueError('Canonical reviewer UUIDv7 required')
 container['env'].append(dict(name='BRAIN_TEST_PUBLIC_REVIEWER_ID',value=value))
 container['command'][-1]+=' -Dtest=PublicReviewerKafkaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false'
if '--gateway' in sys.argv:
 container['command'][-1]+=' -pl services/gateway-service -am'
if '--identity' in sys.argv:
 job['spec']['template']['spec']['containers'][0]['command'][-1]+=' -pl services/identity-service -am'
if '--public-capability-principal' in sys.argv:
 if '--space' not in sys.argv or '--space-kafka' not in sys.argv:raise ValueError('Capability CDC check requires Space and its read-only Kafka role')
 principal=str(uuid.UUID(sys.argv[sys.argv.index('--public-capability-principal')+1]));assert uuid.UUID(principal).version==7
 container['env'].append(dict(name='BRAIN_TEST_PUBLIC_CAPABILITY_PRINCIPAL',value=principal))
 container['command'][-1]+=' -Dtest=PublicCapabilityKafkaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false'
if '--space-kafka' in sys.argv:
 container['env'].append(dict(name='BRAIN_TEST_SPACE_KAFKA_BOOTSTRAP',value='brain-kafka-kafka-bootstrap.brain-events.svc:9093'))
 container['volumeMounts'].append(dict(name='space-kafka-test',mountPath='/space-kafka-test',readOnly=True))
 job['spec']['template']['spec']['volumes'].append(dict(name='space-kafka-test',secret=dict(secretName='brain-space-validator')))
if cdc:
 container=job['spec']['template']['spec']['containers'][0]
 container['env'].append(dict(name='BRAIN_TEST_KAFKA_BOOTSTRAP',value='brain-kafka-kafka-bootstrap.brain-events.svc:9093'))
 container['volumeMounts'].append(dict(name='kafka-test',mountPath='/kafka-test',readOnly=True))
 job['spec']['template']['spec']['volumes'].append(dict(name='kafka-test',secret=dict(secretName='brain-cdc-validator')))
run(['apply','-f','-'],json.dumps(job))
job=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))
for source_name in source_maps:
 run(['patch','configmap',source_name,'-n','brain-data','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':job['metadata']['uid']}]}})])
print('JOB='+name,flush=True)
