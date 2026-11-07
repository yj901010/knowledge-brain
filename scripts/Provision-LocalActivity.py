import base64,datetime,json,secrets,hashlib,subprocess
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID,ExtendedKeyUsageOID
r=Path(__file__).resolve().parents[1];k=['C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(r/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,encoding='utf-8',capture_output=True)
 if p.returncode:raise RuntimeError('Scoped Activity provisioning failed')
 return p.stdout
assert run(['config','current-context']).strip()=='k3d-knowledge-brain'
def get(name,ns):
 s=run(['get','secret',name,'-n',ns,'--ignore-not-found','-o','json']);return json.loads(s)['data'] if s.strip() else None
def apply(name,ns,data):run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='Secret',metadata=dict(name=name,namespace=ns),type='Opaque',data=data)))
def enc(v):return base64.b64encode(v).decode()
ca=get('brain-guest-api-ca','brain-data');assert ca
key=serialization.load_pem_private_key(base64.b64decode(ca['ca.key']),None);cert=x509.load_pem_x509_certificate(base64.b64decode(ca['ca.crt']));now=datetime.datetime.now(datetime.timezone.utc);assert cert.not_valid_after_utc>now+datetime.timedelta(days=1)
for name,ns,hosts in [('brain-activity-api-tls','brain-apps',['activity-service','activity-service.brain-apps.svc','activity-service.brain-apps.svc.cluster.local']),('brain-clickhouse-tls','brain-data',['brain-clickhouse','brain-clickhouse.brain-data.svc','brain-clickhouse.brain-data.svc.cluster.local'])]:
 existing=get(name,ns)
 if existing and x509.load_pem_x509_certificate(base64.b64decode(existing['tls.crt'])).not_valid_after_utc>now+datetime.timedelta(days=1):continue
 leafkey=rsa.generate_private_key(public_exponent=65537,key_size=2048);subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,hosts[1])]);leaf=x509.CertificateBuilder().subject_name(subject).issuer_name(cert.subject).public_key(leafkey.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(min(now+datetime.timedelta(days=60),cert.not_valid_after_utc)).add_extension(x509.BasicConstraints(ca=False,path_length=None),True).add_extension(x509.SubjectKeyIdentifier.from_public_key(leafkey.public_key()),False).add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(key.public_key()),False).add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]),False).add_extension(x509.SubjectAlternativeName([x509.DNSName(h) for h in hosts]),False).sign(key,hashes.SHA256());apply(name,ns,{'tls.crt':enc(leaf.public_bytes(serialization.Encoding.PEM)+cert.public_bytes(serialization.Encoding.PEM)),'tls.key':enc(leafkey.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))})
for name in ['brain-activity-space-authorization','brain-learning-activity-export','brain-planning-activity-export','brain-content-activity-export','brain-knowledge-activity-export']:
 data=get(name,'brain-apps') or {'key':enc(secrets.token_urlsafe(32).encode())};apply(name,'brain-apps',data);apply(name,'brain-data',data)
apply('brain-activity-db','brain-apps',{'password':get('brain-activity-db','brain-data')['password']})
for mode in ['reader','projector','admin']:
 name='brain-clickhouse-'+mode if mode=='admin' else 'brain-activity-clickhouse-'+mode
 data=get(name,'brain-data') or {'username':enc(('brain_operator' if mode=='admin' else 'activity_'+mode).encode()),'password':enc(secrets.token_urlsafe(32).encode())};apply(name,'brain-data',data)
 if mode!='admin':apply(name,'brain-apps',data)
admin=get('brain-clickhouse-admin','brain-data');digest=hashlib.sha256(base64.b64decode(admin['password'])).hexdigest();xml='<clickhouse><users><default remove="remove"/><brain_operator><password_sha256_hex>'+digest+'</password_sha256_hex><networks><ip>::/0</ip></networks><profile>default</profile><quota>default</quota><access_management>1</access_management></brain_operator></users></clickhouse>';apply('brain-clickhouse-users','brain-data',{'users.xml':enc(xml.encode())})
run(['apply','-f',str(r/'infra/activity/kafka-access.yaml')]);run(['wait','kafkauser/brain-activity-projector','-n','brain-events','--for=condition=Ready','--timeout=60s']);user=get('brain-activity-projector','brain-events');broker=get('brain-kafka-cluster-ca-cert','brain-events')
for ns in ['brain-apps','brain-data']:apply('brain-activity-kafka',ns,{'ca.crt':broker['ca.crt'],'user.crt':user['user.crt'],'user.key':user['user.key']})
print('PASS dedicated Activity DB/Space/export/Kafka/ClickHouse credentials and server TLS; no credential values printed')
