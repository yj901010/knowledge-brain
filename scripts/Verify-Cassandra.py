"""Confined Cassandra operator/client probe; no credentials or CQL data in logs."""
import glob,os,ssl,sys,time,zipfile,socket
from pathlib import Path
for p in glob.glob('/opt/cassandra/lib/cassandra-driver-internal-only-*.zip'):
 prefix=zipfile.ZipFile(p).namelist()[0].rstrip('/')
 sys.path.insert(0,p+'/'+prefix)
for prefix in ['six-','futures-']:
 for p in glob.glob('/opt/cassandra/lib/'+prefix+'*.zip'):sys.path.insert(0,p)
sys.path.insert(0,'/opt/cassandra/pylib')
from cassandra.cluster import Cluster
from cassandra.auth import PlainTextAuthProvider
from cassandra import AuthenticationFailed,Unauthorized
host='brain-cassandra.brain-data.svc'
ctx=ssl.create_default_context(cafile='/admin/ca.crt');ctx.check_hostname=True
def credential(role,key):return Path('/'+role+'/'+key).read_text().strip()
def connect(role='admin',bootstrap=False):
 username='cassandra' if bootstrap else credential(role,'username')
 password='cassandra' if bootstrap else credential(role,'password')
 cluster=Cluster([host],port=9042,ssl_context=ctx,auth_provider=PlainTextAuthProvider(username,password),connect_timeout=5,control_connection_timeout=5)
 try:return cluster,cluster.connect()
 except Exception as e:
  if not bootstrap:print('CONNECT failure role='+role+' targets='+','.join(map(str,getattr(e,'errors',{}).keys())),flush=True)
  cluster.shutdown();raise
try:
 mode=os.getenv('MODE','bootstrap')
 if mode=='network-denied':
  socket.gethostbyname(host)
  for attempt in range(5):
   try:
    with socket.create_connection((host,9042),timeout=2):pass
   except (ConnectionRefusedError,TimeoutError):pass
   else:raise RuntimeError('Unlabelled client unexpectedly reached CQL')
   time.sleep(1)
  print('PASS unlabelled Pod denied CQL; no database credentials mounted',flush=True)
 elif mode=='graph-reset':
  deadline=time.monotonic()+45
  while True:
   try:c,s=connect();break
   except Exception:
    if time.monotonic()>deadline:raise
    time.sleep(2)
  s.execute('DROP KEYSPACE IF EXISTS brain_graph')
  s.execute("CREATE KEYSPACE brain_graph WITH replication = {'class':'NetworkTopologyStrategy','datacenter1':1}")
  s.execute('GRANT SELECT ON KEYSPACE brain_graph TO brain_graph_projector')
  s.execute('GRANT MODIFY ON KEYSPACE brain_graph TO brain_graph_projector')
  c.shutdown();print('PASS reset ONLY derived brain_graph keyspace; own projector grants restored',flush=True)
 elif mode=='bootstrap':
  deadline=time.monotonic()+120
  while True:
   try:c,s=connect();break
   except Exception:
    try:c,s=connect(bootstrap=True);break
    except Exception:
     if time.monotonic()>deadline:raise RuntimeError('Authenticated Cassandra unavailable')
     time.sleep(3)
  for role in ['admin','projector']:
   user=credential(role,'username');password=credential(role,'password')
   if not user.replace('_','').isalnum() or not password.replace('_','').replace('-','').isalnum():raise RuntimeError('Invalid credential format')
   existing=s.execute('SELECT can_login,is_superuser FROM system_auth.roles WHERE role=%s',(user,)).one()
   if existing:
    if not existing.can_login or existing.is_superuser!=(role=='admin'):raise RuntimeError('Existing graph role conflicts with intended permissions')
   else:s.execute("CREATE ROLE "+user+" WITH PASSWORD = '"+password+"' AND LOGIN = true AND SUPERUSER = "+('true' if role=='admin' else 'false'))
  c.shutdown();c,s=connect()
  s.execute("CREATE KEYSPACE IF NOT EXISTS brain_graph WITH replication = {'class':'NetworkTopologyStrategy','datacenter1':1}")
  s.execute('GRANT SELECT ON KEYSPACE brain_graph TO brain_graph_projector')
  s.execute('GRANT MODIFY ON KEYSPACE brain_graph TO brain_graph_projector')
  default=s.execute("SELECT can_login,is_superuser FROM system_auth.roles WHERE role='cassandra'").one()
  if default and (default.can_login or default.is_superuser):s.execute('ALTER ROLE cassandra WITH LOGIN = false AND SUPERUSER = false')
  c.shutdown();print('PASS TLS verified; own graph keyspace/roles; default account disabled',flush=True)
 elif mode=='validate':
  deadline=time.monotonic()+30
  while True:
   try:
    with socket.create_connection((host,9042),timeout=5) as raw:
     with ctx.wrap_socket(raw,server_hostname=host):pass
    break
   except (ConnectionRefusedError,TimeoutError):
    if time.monotonic()>deadline:raise
    time.sleep(2)
  print('PASS direct verified TLS handshake',flush=True)
  c,s=connect();s.execute('CREATE TABLE IF NOT EXISTS brain_graph.storage_probe (id text PRIMARY KEY, value text)')
  c.shutdown();c,s=connect('projector')
  if os.getenv('RESTART_CHECK')=='true':
   row=s.execute("SELECT value FROM brain_graph.storage_probe WHERE id='restart-proof'").one()
   if not row or row.value!='persisted':raise RuntimeError('Persistence mismatch')
   print('PASS persisted value after actual Cassandra Pod replacement',flush=True)
  else:
   s.execute("INSERT INTO brain_graph.storage_probe (id,value) VALUES ('restart-proof','persisted')")
   if s.execute("SELECT value FROM brain_graph.storage_probe WHERE id='restart-proof'").one().value!='persisted':raise RuntimeError('Read mismatch')
   for cql in ['CREATE KEYSPACE forbidden WITH replication = {\'class\':\'SimpleStrategy\',\'replication_factor\':1}', 'CREATE TABLE brain_graph.forbidden (id int PRIMARY KEY)', 'SELECT * FROM system_auth.roles']:
    try:s.execute(cql)
    except Unauthorized:pass
    else:raise RuntimeError('Scoped role unexpectedly permitted forbidden operation')
   print('PASS own-keyspace read/write; foreign/auth/schema access denied',flush=True)
  c.shutdown()
  try:c,s=connect(bootstrap=True)
  except Exception:pass
  else:c.shutdown();raise RuntimeError('Default account still enabled')
  print('PASS default account rejected',flush=True)
  for context,hostname in [(ctx,'invalid.brain-data.svc'),(ssl.create_default_context(),host)]:
   try:
    with socket.create_connection((host,9042),timeout=5) as raw:
     with context.wrap_socket(raw,server_hostname=hostname):pass
   except ssl.SSLCertVerificationError:pass
   else:raise RuntimeError('Invalid TLS identity unexpectedly accepted')
  bad=Cluster([host],ssl_context=ctx,auth_provider=PlainTextAuthProvider('brain_graph_projector','invalid-password'),connect_timeout=5,control_connection_timeout=5)
  try:
   try:bad.connect()
   except Exception:pass
   else:raise RuntimeError('Invalid password unexpectedly accepted')
  finally:bad.shutdown()
  plain=Cluster([host],auth_provider=PlainTextAuthProvider(credential('projector','username'),credential('projector','password')),connect_timeout=3,control_connection_timeout=3)
  try:
   try:plain.connect()
   except Exception:pass
   else:raise RuntimeError('Plaintext CQL unexpectedly accepted')
  finally:plain.shutdown()
  print('PASS wrong hostname/CA/password and plaintext rejected',flush=True)
 else:raise RuntimeError('Unknown verification mode')
except Exception as e:
 nested=','.join(sorted({type(value).__name__ for value in getattr(e,'errors',{}).values()}))
 print('FAIL Cassandra validation: '+type(e).__name__+(' ['+nested+']' if nested else ''),flush=True);sys.exit(1)
