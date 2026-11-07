"""Explicit operator capability, scoped to an existing local designated reviewer.
Provenance is an operator assertion, not automatic professional accreditation.
"""
import argparse,secrets,time,uuid,json
from local_recovery import Cluster,ROOT,now,write_json
p=argparse.ArgumentParser();p.add_argument('--principal-id',required=True);p.add_argument('--domain',choices=['MEDICINE','DEVELOPMENT'],required=True);p.add_argument('--capability',choices=['CLAIM_EXPERT_VERIFY','MAPPING_OFFICIAL_REVIEW'],required=True);p.add_argument('--provenance',required=True);p.add_argument('--revoke',action='store_true');a=p.parse_args()
principal=uuid.UUID(a.principal_id)
if principal.version!=7 or str(principal)!=a.principal_id:raise ValueError('Canonical application UUIDv7 required')
if not a.provenance.strip() or len(a.provenance)>2000 or any(ord(ch)<32 for ch in a.provenance):raise ValueError('Bounded provenance required; never include secrets')
c=Cluster(ROOT/'.local/kubeconfig.yaml','knowledge-brain');uid=c.uid();contract_proof=json.loads((ROOT/'.local/public-knowledge-contracts.json').read_text());assert contract_proof['sourceUid']==uid,'Prepare current-cluster contracts before capability writes';space='0199e000-0000-7000-8000-00000000000'+('1' if a.domain=='MEDICINE' else '2');actor='0199e000-0000-7000-8000-0000000000ff'
millis=int(time.time()*1000);event=str(uuid.UUID(int=(millis<<80)|(7<<76)|(secrets.randbits(12)<<64)|(2<<62)|secrets.randbits(62)));trace=secrets.token_hex(16);state='RETIRED' if a.revoke else 'ACTIVE'
def varint(v):
 out=bytearray()
 while v>127:out.append((v&127)|128);v>>=7
 out.append(v);return bytes(out)
def field(n,v):
 if isinstance(v,int):return varint(n<<3)+varint(v)
 if isinstance(v,str):v=v.encode()
 return varint(n<<3|2)+varint(len(v))+v
payload=b''.join(field(i,v) for i,v in enumerate([str(principal),a.capability,state,a.provenance],1))
wire=b''.join(field(i,v) for i,v in enumerate([event,'PublicCapabilityChanged',1,millis,'space-service','PublicCapability',str(principal),space,actor,trace,payload],1))
literal=lambda v:"convert_from(decode('"+v.encode().hex()+"','hex'),'UTF8')"
sql=f"""BEGIN;
SELECT pg_advisory_xact_lock(hashtextextended('{space}:{principal}',0));
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM public_knowledge_space c JOIN space s ON s.space_id=c.space_id WHERE c.space_id='{space}' AND s.state='ACTIVE' AND s.kind='PUBLIC' AND s.visibility='PUBLIC') THEN RAISE EXCEPTION 'Active public Space required'; END IF;
 IF NOT EXISTS(SELECT 1 FROM guest_space_claim WHERE registered_principal_id='{principal}') THEN RAISE EXCEPTION 'Registered claimed application account required'; END IF;
 IF {'false' if a.revoke else 'true'} AND NOT EXISTS(SELECT 1 FROM space_membership WHERE space_id='{space}' AND principal_id='{principal}' AND role IN ('REVIEWER','ADMIN','OWNER') AND state='ACTIVE') THEN RAISE EXCEPTION 'Current designated reviewer membership required'; END IF;
END $$;
INSERT INTO public_knowledge_capability VALUES ('{space}','{principal}','{a.capability}',{literal(a.provenance)},'{state}',CURRENT_TIMESTAMP)
ON CONFLICT(space_id,principal_id,capability) DO UPDATE SET provenance=EXCLUDED.provenance,state=EXCLUDED.state,updated_at=EXCLUDED.updated_at;
INSERT INTO public_knowledge_capability_revision VALUES ('{event}','{space}','{principal}','{a.capability}',{literal(a.provenance)},'{state}',CURRENT_TIMESTAMP);
INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload)
VALUES ('{event}','public-capability','{principal}','PublicCapabilityChanged',to_timestamp({millis}/1000.0),'{trace}','space','public-capability-events','1',decode('{wire.hex()}','hex'));
COMMIT;"""
name='public-capability-'+str(int(time.time()))+'-'+secrets.token_hex(3)
c.apply([dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-data'),data={'role.sql':sql})])
job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=120,ttlSecondsAfterFinished=3600,template=dict(spec=dict(restartPolicy='Never',automountServiceAccountToken=False,containers=[dict(name='assign',image='postgres:17.11-bookworm',command=['psql','-X','-v','ON_ERROR_STOP=1','-h','brain-postgres.brain-data.svc','-U','brain_space','-d','brain_space','-f','/source/role.sql'],env=[dict(name='PGPASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-space-db',key='password')))],resources=dict(requests=dict(cpu='50m',memory='32Mi'),limits=dict(cpu='250m',memory='128Mi')),volumeMounts=[dict(name='source',mountPath='/source',readOnly=True)])],volumes=[dict(name='source',configMap=dict(name=name))]))))
c.apply([job]);job_uid=c.get('job',name,'brain-data')['metadata']['uid'];c.run('patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps(dict(metadata=dict(ownerReferences=[dict(apiVersion='batch/v1',kind='Job',name=name,uid=job_uid)]))))
for _ in range(45):
 status=c.get('job',name,'brain-data').get('status',{})
 if status.get('failed'):raise RuntimeError('Capability operation failed: '+name)
 if status.get('succeeded'):print('PASS local scoped capability '+state+'; atomic Space audit and Outbox; JOB='+name,flush=True);break
 time.sleep(3)
else:raise TimeoutError('Capability operation deadline')
