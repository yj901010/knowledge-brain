"""Explicit local operator grant/revoke; Space-owned DB and atomic role Outbox."""
import argparse,base64,json,secrets,shutil,subprocess,time,uuid
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--principal-id',required=True);p.add_argument('--domain',choices=['MEDICINE','DEVELOPMENT'],required=True);p.add_argument('--revoke',action='store_true');a=p.parse_args()
principal=uuid.UUID(a.principal_id)
if principal.version!=7 or str(principal)!=a.principal_id:raise ValueError('Canonical registered application UUIDv7 required')
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 r=subprocess.run(k+args,input=body,text=True,encoding='utf-8',capture_output=True)
 if r.returncode:raise RuntimeError('Local reviewer operator command failed')
 return r.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
space='0199e000-0000-7000-8000-00000000000'+('1' if a.domain=='MEDICINE' else '2');actor='0199e000-0000-7000-8000-0000000000ff'
millis=int(time.time()*1000);event=str(uuid.UUID(int=(millis<<80)|(7<<76)|(secrets.randbits(12)<<64)|(2<<62)|secrets.randbits(62)));trace=secrets.token_hex(16)
def varint(value):
 result=bytearray()
 while value>127:result.append((value&127)|128);value>>=7
 result.append(value);return bytes(result)
def field(index,value):
 if isinstance(value,int):return varint(index<<3)+varint(value)
 if isinstance(value,str):value=value.encode()
 return varint(index<<3|2)+varint(len(value))+value
state='RETIRED' if a.revoke else 'ACTIVE';kind='PublicReviewerRetired' if a.revoke else 'PublicReviewerAssigned'
payload=b''.join(field(i,v) for i,v in enumerate([space,str(principal),'REVIEWER',state],1))
wire=b''.join(field(i,v) for i,v in enumerate([event,kind,1,millis,'space-service','PublicReviewer',str(principal),space,actor,trace,payload],1))
# All interpolated values below are canonical UUIDs, fixed enums, hex or digits.
mutation=(f"UPDATE space_membership SET state='RETIRED' WHERE space_id='{space}' AND principal_id='{principal}' AND role='REVIEWER';" if a.revoke else f"INSERT INTO space_membership(space_id,principal_id,role,state) VALUES ('{space}','{principal}','REVIEWER','ACTIVE') ON CONFLICT(space_id,principal_id) DO UPDATE SET state='ACTIVE' WHERE space_membership.role='REVIEWER';")
sql=f"""BEGIN;
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM guest_space_claim WHERE registered_principal_id='{principal}') THEN RAISE EXCEPTION 'Claimed registered application principal required'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public_knowledge_space c JOIN space s ON s.space_id=c.space_id WHERE c.space_id='{space}' AND s.state='ACTIVE' AND s.kind='PUBLIC' AND s.visibility='PUBLIC') THEN RAISE EXCEPTION 'Active public Space required'; END IF;
 IF EXISTS(SELECT 1 FROM space_membership WHERE space_id='{space}' AND principal_id='{principal}' AND role <> 'REVIEWER') THEN RAISE EXCEPTION 'Existing role requires separate administration'; END IF;
END $$;
{mutation}
INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload)
VALUES ('{event}','public-knowledge','{principal}','{kind}',to_timestamp({millis}/1000.0),'{trace}','space','public-knowledge-events','1',decode('{wire.hex()}','hex'));
COMMIT;
"""
name='public-reviewer-'+str(int(time.time()))+'-'+secrets.token_hex(3)
run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-data'),data={'role.sql':sql})))
job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=120,ttlSecondsAfterFinished=3600,template=dict(spec=dict(restartPolicy='Never',automountServiceAccountToken=False,containers=[dict(name='assign',image='postgres:17.11-bookworm',command=['psql','-X','-v','ON_ERROR_STOP=1','-h','brain-postgres.brain-data.svc','-U','brain_space','-d','brain_space','-f','/source/role.sql'],env=[dict(name='PGPASSWORD',valueFrom=dict(secretKeyRef=dict(name='brain-space-db',key='password')))],resources=dict(requests=dict(cpu='25m',memory='32Mi'),limits=dict(cpu='250m',memory='96Mi')),volumeMounts=[dict(name='source',mountPath='/source',readOnly=True)])],volumes=[dict(name='source',configMap=dict(name=name))]))))
run(['apply','-f','-'],json.dumps(job));uid=json.loads(run(['get','job',name,'-n','brain-data','-o','json']))['metadata']['uid'];run(['patch','configmap',name,'-n','brain-data','--type=merge','-p',json.dumps({'metadata':{'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':name,'uid':uid}]}})])
for _ in range(120):
 status=json.loads(run(['get','job',name,'-n','brain-data','-o','json'])).get('status',{})
 if status.get('failed'):raise RuntimeError('Reviewer operation failed; inspect operator Job '+name)
 if status.get('succeeded'):print('PASS reviewer '+state+' '+str(principal)+' '+a.domain+' JOB='+name);break
 time.sleep(1)
else:raise RuntimeError('Reviewer operation timeout '+name)
