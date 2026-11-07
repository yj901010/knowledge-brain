"""Provision only local Integration server TLS, Space capability and own DB credential."""
import base64,datetime,json,secrets,subprocess
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID,ExtendedKeyUsageOID
root=Path(__file__).resolve().parents[1]
k=['C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,encoding='utf-8',capture_output=True)
 if p.returncode:raise RuntimeError('Local Integration provisioning failed')
 return p.stdout
assert run(['config','current-context']).strip()=='k3d-knowledge-brain'
def get(name,ns):
 raw=run(['get','secret',name,'-n',ns,'--ignore-not-found','-o','json']);return json.loads(raw)['data'] if raw.strip() else None
def apply(name,ns,data):run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='Secret',metadata=dict(name=name,namespace=ns),type='Opaque',data=data)))
def enc(value):return base64.b64encode(value).decode()
now=datetime.datetime.now(datetime.timezone.utc);existing=get('brain-integration-api-tls','brain-apps')
if not existing or x509.load_pem_x509_certificate(base64.b64decode(existing['tls.crt'])).not_valid_after_utc<now+datetime.timedelta(days=1):
 ca=get('brain-guest-api-ca','brain-data');key=serialization.load_pem_private_key(base64.b64decode(ca['ca.key']),None);issuer=x509.load_pem_x509_certificate(base64.b64decode(ca['ca.crt']));assert issuer.not_valid_after_utc>now+datetime.timedelta(days=1)
 leafkey=rsa.generate_private_key(public_exponent=65537,key_size=2048);hosts=['integration-service','integration-service.brain-apps.svc','integration-service.brain-apps.svc.cluster.local']
 leaf=x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,hosts[1])])).issuer_name(issuer.subject).public_key(leafkey.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(min(now+datetime.timedelta(days=60),issuer.not_valid_after_utc)).add_extension(x509.BasicConstraints(ca=False,path_length=None),True).add_extension(x509.SubjectAlternativeName([x509.DNSName(h) for h in hosts]),False).add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]),False).sign(key,hashes.SHA256())
 apply('brain-integration-api-tls','brain-apps',{'tls.crt':enc(leaf.public_bytes(serialization.Encoding.PEM)+issuer.public_bytes(serialization.Encoding.PEM)),'tls.key':enc(leafkey.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))})
name='brain-integration-space-authorization';capability=get(name,'brain-apps') or {'key':enc(secrets.token_urlsafe(32).encode())}
for ns in ['brain-apps','brain-data']:apply(name,ns,capability)
apply('brain-integration-db','brain-apps',{'password':get('brain-integration-db','brain-data')['password']})
name='brain-integration-activity-export';capability=get(name,'brain-apps') or {'key':enc(secrets.token_urlsafe(32).encode())}
for ns in ['brain-apps','brain-data']:apply(name,ns,capability)
print('PASS dedicated Integration HTTPS/Space/own-DB credentials provisioned; no values printed')
