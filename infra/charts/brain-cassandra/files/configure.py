import os,re,shutil,socket
from pathlib import Path
for source in Path('/etc/cassandra').iterdir():
 target=Path('/config')/source.name
 if source.is_dir():shutil.copytree(source,target,dirs_exist_ok=True)
 else:shutil.copy2(source,target)
p=Path('/config/cassandra.yaml');s=p.read_text()
def setting(key,value):
 global s
 pattern=r'^'+re.escape(key)+r':.*$'
 if re.search(pattern,s,re.M):s=re.sub(pattern,key+': '+value,s,flags=re.M)
 else:s+='\n'+key+': '+value+'\n'
ordinal=os.environ['HOSTNAME'].rsplit('-',1)[1]
stable=socket.gethostbyname('brain-cassandra-'+ordinal+'.brain-data.svc')
setting('cluster_name',"'knowledge-brain-graph'")
setting('listen_address',os.environ['POD_IP'])
setting('rpc_address','0.0.0.0')
setting('broadcast_address',stable)
setting('broadcast_rpc_address',stable)
setting('authenticator','PasswordAuthenticator')
setting('authorizer','CassandraAuthorizer')
setting('num_tokens','16')
setting('batch_size_warn_threshold_in_kb','64')
setting('batch_size_fail_threshold_in_kb','1024')
s=re.sub(r'(- seeds:).*',r'\1 "brain-cassandra-0.brain-data.svc"',s)
password=Path('/server/store-password').read_text().strip()
for name,block in [
 ('client_encryption_options',f"""  enabled: true
  optional: false
  keystore: /server/server.p12
  keystore_password: '{password}'
  store_type: PKCS12
  require_client_auth: false
"""),
 ('server_encryption_options',f"""  internode_encryption: all
  keystore: /server/server.p12
  keystore_password: '{password}'
  truststore: /trust/truststore.p12
  truststore_password: '{password}'
  store_type: PKCS12
  require_client_auth: true
  require_endpoint_verification: true
""")]:
 s=re.sub(r'(?ms)^'+name+r':.*?(?=^[a-zA-Z_][a-zA-Z_0-9]*:|\Z)',name+':\n'+block+'\n',s)
p.write_text(s)

# File/GC rotation must fit the chart's 64MiB log EmptyDir. Upstream defaults
# retain gigabytes and can evict a healthy node after prolonged local validation.
logback=Path('/config/logback.xml');s=logback.read_text()
for tag,value in [('maxFileSize','4MB'),('maxHistory','2'),('totalSizeCap','8MB')]:
 s=re.sub(r'<'+tag+r'>[^<]*</'+tag+r'>','<'+tag+'>'+value+'</'+tag+'>',s)
logback.write_text(s)
for options in Path('/config').glob('jvm*-server.options'):
 s=options.read_text().replace('-XX:NumberOfGCLogFiles=10','-XX:NumberOfGCLogFiles=3').replace('-XX:GCLogFileSize=10M','-XX:GCLogFileSize=4M')
 # Activate the pinned image's Java11 GC option so cassandra-env.sh does not
 # insert its larger default. Java8 keeps its existing equivalent flags.
 if options.name=='jvm11-server.options':
  s=re.sub(r'^#?-Xlog:gc[^\n]*$', '-Xlog:gc=info,heap*=trace,age*=debug,safepoint=info,promotion*=trace:file=/var/log/cassandra/gc.log:time,uptime,pid,tid,level:filecount=3,filesize=4194304',s,flags=re.M)
 options.write_text(s)
