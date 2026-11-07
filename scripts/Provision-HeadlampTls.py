"""Local-only certificate and credential bootstrap. Never prints secret material."""
import base64,datetime,json,secrets,shutil,subprocess
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID,ExtendedKeyUsageOID
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 result=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if result.returncode:raise RuntimeError(result.stderr)
 return result.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
def get(name,ns):
 value=run(['get','secret',name,'-n',ns,'--ignore-not-found','-o','json'])
 return json.loads(value)['data'] if value.strip() else None
def apply(name,ns,data):
 run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='Secret',metadata=dict(name=name,namespace=ns),type='Opaque',data=data)))
def encoded(value):return base64.b64encode(value).decode()
now=datetime.datetime.now(datetime.timezone.utc)
ca=get('brain-guest-api-ca','brain-data')
if not ca:raise RuntimeError('Provision local API CA first')
ca_cert=x509.load_pem_x509_certificate(base64.b64decode(ca['ca.crt']))
if ca_cert.not_valid_after_utc < now+datetime.timedelta(days=1):raise RuntimeError('Local API CA requires operator rotation')
existing=get('brain-headlamp-tls','brain-ops')
renew=not existing
if existing:
 leaf=x509.load_pem_x509_certificate(base64.b64decode(existing['tls.crt']))
 renew=leaf.not_valid_after_utc < now+datetime.timedelta(days=1) or leaf.issuer!=ca_cert.subject
if renew:
 ca_key=serialization.load_pem_private_key(base64.b64decode(ca['ca.key']),None)
 key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
 subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'brain-headlamp.brain-ops.svc')])
 hosts=['localhost','brain-headlamp','brain-headlamp.brain-ops.svc','brain-headlamp.brain-ops.svc.cluster.local']
 cert=x509.CertificateBuilder().subject_name(subject).issuer_name(ca_cert.subject).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(min(now+datetime.timedelta(days=60),ca_cert.not_valid_after_utc)).add_extension(x509.BasicConstraints(ca=False,path_length=None),True).add_extension(x509.KeyUsage(True,False,True,False,False,False,False,False,False),True).add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()),False).add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()),False).add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]),False).add_extension(x509.SubjectAlternativeName([x509.DNSName(h) for h in hosts]),False).sign(ca_key,hashes.SHA256())
 apply('brain-headlamp-tls','brain-ops',{'tls.crt':encoded(cert.public_bytes(serialization.Encoding.PEM)+ca_cert.public_bytes(serialization.Encoding.PEM)),'tls.key':encoded(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))})
apply('brain-headlamp-trust','brain-ops',{'ca.crt':ca['ca.crt']})
print('Provisioned Headlamp leaf TLS/public trust; no CA private key or token exported')
