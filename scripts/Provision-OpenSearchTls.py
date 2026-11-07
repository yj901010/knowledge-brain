"""Local OpenSearch mTLS bootstrap; no private material is written to disk or stdout."""
import base64,datetime,json,shutil,subprocess
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID,ExtendedKeyUsageOID
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if p.returncode:raise RuntimeError(p.stderr)
 return p.stdout
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
def get(name,ns):
 text=run(['get','secret',name,'-n',ns,'--ignore-not-found','-o','json'])
 return json.loads(text)['data'] if text.strip() else None
def apply(name,ns,data):
 run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='Secret',metadata=dict(name=name,namespace=ns),type='Opaque',data=data)))
def b64(value):return base64.b64encode(value).decode()
now=datetime.datetime.now(datetime.timezone.utc)
ca=get('brain-guest-api-ca','brain-data')
if not ca:raise RuntimeError('Provision the existing local API CA first')
cert=x509.load_pem_x509_certificate(base64.b64decode(ca['ca.crt']))
if cert.not_valid_after_utc<now+datetime.timedelta(days=1):raise RuntimeError('Local CA rotation required')
ca_key=serialization.load_pem_private_key(base64.b64decode(ca['ca.key']),None)
for name,cn,server in [('brain-opensearch-node-tls','brain-opensearch',True),('brain-search-admin-tls','brain-search-admin',False),('brain-search-reader-tls','brain-note-reader',False),('brain-search-projector-tls','brain-note-projector',False)]:
 data=get(name,'brain-data');renew=not data
 if data:
  leaf=x509.load_pem_x509_certificate(base64.b64decode(data['tls.crt']))
  renew=leaf.not_valid_after_utc<now+datetime.timedelta(days=1) or leaf.issuer!=cert.subject or data.get('ca.crt')!=ca['ca.crt']
 if renew:
  key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
  builder=x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,cn)])).issuer_name(cert.subject).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(min(now+datetime.timedelta(days=60),cert.not_valid_after_utc)).add_extension(x509.BasicConstraints(ca=False,path_length=None),True).add_extension(x509.KeyUsage(True,False,True,False,False,False,False,False,False),True).add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()),False).add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()),False).add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.CLIENT_AUTH]+([ExtendedKeyUsageOID.SERVER_AUTH] if server else [])),False)
  if server:
   hosts=['localhost','brain-opensearch','brain-opensearch.brain-data.svc','brain-opensearch.brain-data.svc.cluster.local','brain-opensearch-0','brain-opensearch-0.brain-opensearch-nodes.brain-data.svc','brain-opensearch-0.brain-opensearch-nodes.brain-data.svc.cluster.local']
   builder=builder.add_extension(x509.SubjectAlternativeName([x509.DNSName(h) for h in hosts]),False)
  leaf=builder.sign(ca_key,hashes.SHA256())
  data={'tls.crt':b64(leaf.public_bytes(serialization.Encoding.PEM)+cert.public_bytes(serialization.Encoding.PEM)),'tls.key':b64(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())),'ca.crt':ca['ca.crt']}
  apply(name,'brain-data',data)
 if name in ['brain-search-reader-tls','brain-search-projector-tls']:apply(name,'brain-apps',data)
print('Provisioned separate node/admin/reader/projector mTLS; no CA key exported')
