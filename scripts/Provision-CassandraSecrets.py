"""Create scoped local graph credentials and a leaf PKCS12; never export the CA key."""
import base64,datetime,ipaddress,json,secrets,shutil,subprocess
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID,ExtendedKeyUsageOID
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(args,body=None):
 p=subprocess.run(k+args,input=body,text=True,capture_output=True)
 if p.returncode:raise RuntimeError('Local Cassandra provisioning command failed')
 return p.stdout
def get(name,ns='brain-data'):
 result=run(['get','secret',name,'-n',ns,'--ignore-not-found','-o','json'])
 return json.loads(result)['data'] if result.strip() else None
def put(name,ns,data):
 run(['apply','-f','-'],json.dumps(dict(apiVersion='v1',kind='Secret',metadata=dict(name=name,namespace=ns),type='Opaque',data=data)))
def b64(v):return base64.b64encode(v).decode()
if run(['config','current-context']).strip()!='k3d-knowledge-brain':raise RuntimeError('Local context required')
ca=get('brain-guest-api-ca');now=datetime.datetime.now(datetime.timezone.utc)
cert=x509.load_pem_x509_certificate(base64.b64decode(ca['ca.crt']))
if cert.not_valid_after_utc<now+datetime.timedelta(days=1):raise RuntimeError('Rotate expired local CA first')
services=json.loads(run(['get','service','-n','brain-data','-o','json']))['items']
services=[s for s in services if s['metadata']['name']=='brain-cassandra' or s['metadata']['name'].startswith('brain-cassandra-') and s['metadata']['name']!='brain-cassandra-nodes']
if not services:raise RuntimeError('Install Cassandra Services before provisioning stable SANs')
names=[];ips=[]
for service in services:
 name=service['metadata']['name'];names.extend([name,name+'.brain-data.svc',name+'.brain-data.svc.cluster.local']);ips.append(ipaddress.ip_address(service['spec']['clusterIP']))
data=get('brain-cassandra-node-tls');renew=not data
if data:
 leaf=x509.load_pem_x509_certificate(base64.b64decode(data['tls.crt']))
 san=leaf.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
 renew=leaf.not_valid_after_utc<now+datetime.timedelta(days=1) or data.get('ca.crt')!=ca['ca.crt'] or not set(ips).issubset(set(san.get_values_for_type(x509.IPAddress)))
if renew:
 key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
 ca_key=serialization.load_pem_private_key(base64.b64decode(ca['ca.key']),None)
 leaf=x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'brain-cassandra')])).issuer_name(cert.subject).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(min(now+datetime.timedelta(days=60),cert.not_valid_after_utc)).add_extension(x509.BasicConstraints(ca=False,path_length=None),True).add_extension(x509.SubjectAlternativeName([x509.DNSName(n) for n in names]+[x509.IPAddress(ip) for ip in ips]),False).add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH,ExtendedKeyUsageOID.CLIENT_AUTH]),False).sign(ca_key,hashes.SHA256())
 password=secrets.token_urlsafe(32).encode()
 data={'server.p12':b64(pkcs12.serialize_key_and_certificates(b'brain-cassandra',key,leaf,[cert],serialization.BestAvailableEncryption(password))),'store-password':b64(password),'ca.crt':ca['ca.crt'],'tls.crt':b64(leaf.public_bytes(serialization.Encoding.PEM))}
 put('brain-cassandra-node-tls','brain-data',data)
for role in ['admin','projector']:
 name='brain-graph-'+role;data=get(name)
 if not data:data={'username':b64(('brain_graph_'+role).encode()),'password':b64(secrets.token_urlsafe(32).encode())}
 data['ca.crt']=ca['ca.crt'];put(name,'brain-data',data)
 if role=='projector':put(name,'brain-apps',data)
print('PASS scoped graph secrets and stable service DNS/IP certificate; no private key exported')
