"""Idempotent local Valkey TLS/least-privilege ACL bootstrap; never emit credentials."""
import base64, datetime, hashlib, json, secrets, shutil, subprocess
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID

ROOT = Path(__file__).resolve().parents[1]
K = [shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe',
     '--kubeconfig', str(ROOT / '.local/kubeconfig.yaml'), '--context', 'k3d-knowledge-brain',
     '--server=https://127.0.0.1:6550']

def run(args, body=None):
    result = subprocess.run(K + args, input=body, capture_output=True, encoding='utf-8', timeout=45)
    if result.returncode:
        raise RuntimeError('Local cache provisioning command failed: ' + result.stderr[-1000:])
    return result.stdout

def get(name, namespace):
    value = run(['get', 'secret', name, '-n', namespace, '--ignore-not-found', '-o', 'json'])
    return json.loads(value)['data'] if value.strip() else None

def b64(value): return base64.b64encode(value if isinstance(value, bytes) else value.encode()).decode()
def apply(name, namespace, data):
    run(['apply', '-f', '-'], json.dumps(dict(apiVersion='v1', kind='Secret', type='Opaque',
        metadata=dict(name=name, namespace=namespace), data=data)))

def main():
    nodes = json.loads(run(['get', 'nodes', '-o', 'json']))['items']
    if {n['metadata']['name'] for n in nodes} != {'k3d-knowledge-brain-agent-0', 'k3d-knowledge-brain-server-0'}:
        raise RuntimeError('Existing two-node local cluster required')
    ca = get('brain-guest-api-ca', 'brain-data')
    if not ca: raise RuntimeError('Provision existing local API CA first')
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = x509.load_pem_x509_certificate(base64.b64decode(ca['ca.crt']))
    if cert.not_valid_after_utc < now + datetime.timedelta(days=1): raise RuntimeError('Local CA rotation required')
    server = get('brain-valkey-tls', 'brain-data')
    if not server or server.get('ca.crt') != ca['ca.crt'] or x509.load_pem_x509_certificate(
            base64.b64decode(server['tls.crt'])).not_valid_after_utc < now + datetime.timedelta(days=1):
        ca_key = serialization.load_pem_private_key(base64.b64decode(ca['ca.key']), None)
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        leaf = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'brain-valkey')]))
                .issuer_name(cert.subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(now - datetime.timedelta(minutes=5))
                .not_valid_after(min(now + datetime.timedelta(days=60), cert.not_valid_after_utc))
                .add_extension(x509.BasicConstraints(ca=False, path_length=None), True)
                .add_extension(x509.KeyUsage(True, False, True, False, False, False, False, False, False), True)
                .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), False)
                .add_extension(x509.SubjectAlternativeName([x509.DNSName(h) for h in [
                    'brain-valkey', 'brain-valkey.brain-data.svc', 'brain-valkey.brain-data.svc.cluster.local']]), False)
                .sign(ca_key, hashes.SHA256()))
        server = {'ca.crt': ca['ca.crt'], 'tls.crt': b64(leaf.public_bytes(serialization.Encoding.PEM)
                    + cert.public_bytes(serialization.Encoding.PEM)), 'tls.key': b64(key.private_bytes(
                    serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))}
        apply('brain-valkey-tls', 'brain-data', server)
    client = get('brain-public-search-cache', 'brain-apps')
    password = base64.b64decode(client['password']).decode() if client else secrets.token_urlsafe(32)
    if not (43 <= len(password) <= 128 and all(c.isascii() and (c.isalnum() or c in '_-') for c in password)):
        raise RuntimeError('Invalid existing cache credential')
    # Preserve the original credential on repeated runs, including a partially completed first bootstrap.
    apply('brain-public-search-cache', 'brain-apps', {'username': b64('public-search'),
          'password': b64(password), 'ca.crt': ca['ca.crt']})
    acl = get('brain-valkey-acl', 'brain-data')
    health = base64.b64decode(acl['health-password']).decode() if acl else secrets.token_urlsafe(32)
    rules = ('user default off\nuser public-search on #' + hashlib.sha256(password.encode()).hexdigest()
             + ' ~brain:public-search:v1:* -@all +ping +getrange +set +client|setinfo +client|capa\n'
             + 'user health on #' + hashlib.sha256(health.encode()).hexdigest() + ' -@all +ping\n')
    apply('brain-valkey-acl', 'brain-data', {'users.acl': b64(rules), 'health-password': b64(health)})
    print('Provisioned TLS and scoped public-search/health ACLs; existing passwords retained; no private material exported')

if __name__ == '__main__': main()
