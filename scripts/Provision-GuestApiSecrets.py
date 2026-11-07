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
if ca:
 ca_key=serialization.load_pem_private_key(base64.b64decode(ca['ca.key']),None)
 ca_cert=x509.load_pem_x509_certificate(base64.b64decode(ca['ca.crt']))
else:
 ca_key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
 subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'knowledge-brain local guest API CA')])
 ca_cert=x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(ca_key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(now+datetime.timedelta(days=90)).add_extension(x509.BasicConstraints(ca=True,path_length=0),True).add_extension(x509.KeyUsage(False,False,False,False,False,True,True,False,False),True).add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()),False).add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()),False).sign(ca_key,hashes.SHA256())
 ca={'ca.crt':encoded(ca_cert.public_bytes(serialization.Encoding.PEM)),'ca.key':encoded(ca_key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))}
 apply('brain-guest-api-ca','brain-data',ca)
try:ca_cert.extensions.get_extension_for_class(x509.SubjectKeyIdentifier)
except x509.ExtensionNotFound:
 ca_cert=x509.CertificateBuilder().subject_name(ca_cert.subject).issuer_name(ca_cert.subject).public_key(ca_key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(ca_cert.not_valid_after_utc).add_extension(x509.BasicConstraints(ca=True,path_length=0),True).add_extension(x509.KeyUsage(False,False,False,False,False,True,True,False,False),True).add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()),False).add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()),False).sign(ca_key,hashes.SHA256())
 ca['ca.crt']=encoded(ca_cert.public_bytes(serialization.Encoding.PEM));apply('brain-guest-api-ca','brain-data',ca)
if ca_cert.not_valid_after_utc < now+datetime.timedelta(days=1):raise RuntimeError('Local API CA requires operator rotation before expiry')
for service in ('identity','space','gateway','content','media','knowledge','brain-query','learning'):
 name='brain-'+service+'-api-tls'
 existing=get(name,'brain-apps');renew=not existing
 if existing:
  leaf=x509.load_pem_x509_certificate(base64.b64decode(existing['tls.crt']))
  renew=leaf.not_valid_after_utc < now+datetime.timedelta(days=1)
  try:leaf.extensions.get_extension_for_class(x509.AuthorityKeyIdentifier)
  except x509.ExtensionNotFound:renew=True
 if renew:
  key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
  subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,service+'-service.brain-apps.svc')])
  hosts=['localhost',service+'-service',service+'-service.brain-apps.svc',service+'-service.brain-apps.svc.cluster.local']
  cert=x509.CertificateBuilder().subject_name(subject).issuer_name(ca_cert.subject).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(min(now+datetime.timedelta(days=60),ca_cert.not_valid_after_utc)).add_extension(x509.BasicConstraints(ca=False,path_length=None),True).add_extension(x509.KeyUsage(True,False,True,False,False,False,False,False,False),True).add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()),False).add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()),False).add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]),False).add_extension(x509.SubjectAlternativeName([x509.DNSName(h) for h in hosts]),False).sign(ca_key,hashes.SHA256())
  apply(name,'brain-apps',{'tls.crt':encoded(cert.public_bytes(serialization.Encoding.PEM)+ca_cert.public_bytes(serialization.Encoding.PEM)),'tls.key':encoded(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))})
shared=get('brain-guest-introspection','brain-apps') or {'key':encoded(secrets.token_urlsafe(32).encode())}
apply('brain-guest-introspection','brain-apps',shared)
apply('brain-guest-api-trust','brain-apps',{'ca.crt':ca['ca.crt']})
apply('brain-guest-api-trust','brain-data',{'ca.crt':ca['ca.crt']})
apply('brain-identity-db','brain-apps',{'password':get('brain-identity-db','brain-data')['password']})
auth=get('brain-content-space-authorization','brain-apps') or {'key':encoded(secrets.token_urlsafe(32).encode())}
apply('brain-content-space-authorization','brain-apps',auth)
apply('brain-content-db','brain-apps',{'password':get('brain-content-db','brain-data')['password']})

for name,namespaces in [('brain-media-space-authorization',['brain-apps']),('brain-content-media-authorization',['brain-apps']),('brain-content-search-snapshot',['brain-apps']),('brain-query-space-authorization',['brain-apps']),('brain-knowledge-space-authorization',['brain-apps']),('brain-learning-space-authorization',['brain-apps']),('brain-content-knowledge-authorization',['brain-apps'])]:
 value=get(name,'brain-apps') or {'key':encoded(secrets.token_urlsafe(32).encode())}
 for ns in namespaces:apply(name,ns,value)
apply('brain-learning-db','brain-apps',{'password':get('brain-learning-db','brain-data')['password']})
apply('brain-knowledge-db','brain-apps',{'password':get('brain-knowledge-db','brain-data')['password']})
apply('brain-media-db','brain-apps',{'password':get('brain-media-db','brain-data')['password']})
root_creds=get('brain-minio-root','brain-data') or {'accessKey':encoded(b'brain-local-root'),'secretKey':encoded(secrets.token_hex(32).encode())}
apply('brain-minio-root','brain-data',root_creds)
media_creds=get('brain-media-storage','brain-data') or {'accessKey':encoded(b'brain-media-service'),'secretKey':encoded(secrets.token_hex(32).encode())}
for ns in ('brain-data','brain-apps'):apply('brain-media-storage',ns,media_creds)
minio_existing=get('brain-minio-tls','brain-data')
minio_renew=not minio_existing
if minio_existing:
 minio_leaf=x509.load_pem_x509_certificate(base64.b64decode(minio_existing['tls.crt']))
 minio_renew=minio_leaf.not_valid_after_utc < now+datetime.timedelta(days=1) or minio_leaf.issuer != ca_cert.subject
if minio_renew:
 key=rsa.generate_private_key(public_exponent=65537,key_size=2048);subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'brain-minio.brain-data.svc')]);hosts=['localhost','brain-minio','brain-minio.brain-data.svc','brain-minio.brain-data.svc.cluster.local']
 cert=x509.CertificateBuilder().subject_name(subject).issuer_name(ca_cert.subject).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(min(now+datetime.timedelta(days=60),ca_cert.not_valid_after_utc)).add_extension(x509.BasicConstraints(ca=False,path_length=None),True).add_extension(x509.KeyUsage(True,False,True,False,False,False,False,False,False),True).add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()),False).add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()),False).add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]),False).add_extension(x509.SubjectAlternativeName([x509.DNSName(h) for h in hosts]),False).sign(ca_key,hashes.SHA256())
 apply('brain-minio-tls','brain-data',{'tls.crt':encoded(cert.public_bytes(serialization.Encoding.PEM)+ca_cert.public_bytes(serialization.Encoding.PEM)),'tls.key':encoded(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))})
# Only the public local trust anchor is exported; CA private key stays in operator namespace.
(root/'.local/guest-api-ca.crt').write_bytes(base64.b64decode(ca['ca.crt']))
print('Provisioned local API/storage TLS, separate service authorization keys and scoped credentials; values not printed')
