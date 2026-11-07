"""Issue local operator/ingest TLS credentials; never print secret material."""
import base64,datetime,json,secrets,sys
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID,ExtendedKeyUsageOID
from local_recovery import Cluster,ROOT

def main():
 cluster=Cluster(ROOT/'.local/kubeconfig.yaml','knowledge-brain')
 namespace=cluster.get('namespace','brain-observability')
 namespace['metadata']['labels']={'kubernetes.io/metadata.name':'brain-observability','pod-security.kubernetes.io/enforce':'restricted'}
 cluster.run('label','namespace','brain-observability','pod-security.kubernetes.io/enforce=restricted','--overwrite')
 ca=cluster.get('secret','brain-guest-api-ca','brain-data')['data']
 decode=lambda v:base64.b64decode(v)
 encode=lambda v:base64.b64encode(v).decode()
 cert=x509.load_pem_x509_certificate(decode(ca['ca.crt']))
 key=serialization.load_pem_private_key(decode(ca['ca.key']),None)
 now=datetime.datetime.now(datetime.timezone.utc)
 def existing(name,ns):
  value=cluster.run('get','secret',name,'-n',ns,'--ignore-not-found','-o','json')
  return json.loads(value)['data'] if value.strip() else None
 def store(name,ns,data):cluster.apply([dict(apiVersion='v1',kind='Secret',metadata=dict(name=name,namespace=ns),type='Opaque',data=data)])
 def leaf(name,ns,hosts,client=False):
  value=existing(name,ns)
  if value:
   old=x509.load_pem_x509_certificate(decode(value['tls.crt']))
   try:
    old.extensions.get_extension_for_class(x509.AuthorityKeyIdentifier)
    old.extensions.get_extension_for_class(x509.SubjectKeyIdentifier)
    if old.not_valid_after_utc>now+datetime.timedelta(days=1) and value.get('ca.crt')==ca['ca.crt']:return value
   except x509.ExtensionNotFound:pass
  private=rsa.generate_private_key(public_exponent=65537,key_size=2048)
  subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,hosts[0])])
  builder=x509.CertificateBuilder().subject_name(subject).issuer_name(cert.subject).public_key(private.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(min(now+datetime.timedelta(days=60),cert.not_valid_after_utc)).add_extension(x509.BasicConstraints(ca=False,path_length=None),True).add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.CLIENT_AUTH if client else ExtendedKeyUsageOID.SERVER_AUTH]),False).add_extension(x509.SubjectAlternativeName([x509.DNSName(h) for h in hosts]),False)
  builder=builder.add_extension(x509.KeyUsage(True,False,True,False,False,False,False,False,False),True).add_extension(x509.SubjectKeyIdentifier.from_public_key(private.public_key()),False).add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(key.public_key()),False)
  result=builder.sign(key,hashes.SHA256())
  value={'tls.crt':encode(result.public_bytes(serialization.Encoding.PEM)+cert.public_bytes(serialization.Encoding.PEM)),'tls.key':encode(private.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())),'ca.crt':ca['ca.crt']}
  store(name,ns,value);return value
 leaf('brain-otel-server-tls','brain-observability',['brain-otel-collector','brain-otel-collector.brain-observability.svc','brain-otel-collector.brain-observability.svc.cluster.local'])
 leaf('brain-grafana-tls','brain-observability',['localhost','brain-grafana','brain-grafana.brain-observability.svc','brain-grafana.brain-observability.svc.cluster.local'])
 client=leaf('brain-telemetry-client','brain-apps',['brain-telemetry-client'],True)
 store('brain-telemetry-client','brain-observability',client)
 if not existing('brain-grafana-admin','brain-observability'):store('brain-grafana-admin','brain-observability',{'username':encode(b'brain-admin'),'password':encode(secrets.token_urlsafe(32).encode())})
 print('Operator-only TLS, mutual ingest TLS and generated Grafana credentials provisioned; no secret values logged')

if __name__=='__main__':main()
