import ssl,urllib.request,urllib.parse,urllib.error,json,hashlib
from pathlib import Path
origin='https://brain-clickhouse.brain-data.svc:8443';tls=ssl.create_default_context(cafile='/trust/ca.crt')
def sql(query,role='admin'):
 username=Path('/'+role+'/username').read_text().strip();password=Path('/'+role+'/password').read_text().strip()
 request=urllib.request.Request(origin+'/?wait_end_of_query=1',data=query.encode(),headers={'X-ClickHouse-User':username,'X-ClickHouse-Key':password},method='POST')
 try:
  with urllib.request.urlopen(request,context=tls,timeout=15) as response:return response.status,response.read().decode()
 except urllib.error.HTTPError as e:return e.code,e.read().decode()
for statement in Path('/scripts/schema.sql').read_text().split(';'):
 if statement.strip():assert sql(statement)[0]==200,'Schema bootstrap failed'
for mode in ['reader','projector']:
 name='activity_'+mode;password=Path('/'+mode+'/password').read_text().strip();digest=hashlib.sha256(password.encode()).hexdigest();settings='readonly=1,' if mode=='reader' else ''
 assert sql("CREATE USER IF NOT EXISTS "+name+" IDENTIFIED WITH sha256_hash BY '"+digest+"' SETTINGS "+settings+"max_execution_time=5,max_memory_usage=134217728")[0]==200,'Scoped user setup failed'
 assert sql('GRANT '+('SELECT' if mode=='reader' else 'SELECT, INSERT')+' ON brain_activity.timeline TO '+name)[0]==200,'Scoped grant failed'
 for operation,expected in [('SELECT',1),('INSERT',0 if mode=='reader' else 1),('DROP TABLE',0),('ALTER TABLE',0)]:
  status,value=sql('CHECK GRANT '+operation+' ON brain_activity.timeline',mode);assert status==200 and value.strip()==str(expected),'Unexpected '+mode+' capability '+operation
 print('PASS '+mode+' exact timeline privileges and write/DDL boundaries',flush=True)
request=urllib.request.Request(origin+'/?query=SELECT%201')
try:urllib.request.urlopen(request,context=tls,timeout=10);raise AssertionError('Anonymous SQL allowed')
except urllib.error.HTTPError as e:assert e.code in (401,403,404,500)
assert sql('SELECT 1','reader')==(200,'1\n');print('PASS verified TLS, anonymous SQL denial and persistent Activity timeline schema',flush=True)
