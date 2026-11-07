"""Local-only browser graph failure validation; always restore original replicas."""
import argparse,json,re,shutil,subprocess,sys,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(root/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def run(a):
 p=subprocess.run(k+a,text=True,capture_output=True)
 if p.returncode:raise RuntimeError(p.stderr or 'Local validation command failed')
 return p.stdout
def wait_for(callback,seconds=120):
 until=time.monotonic()+seconds
 while time.monotonic()<until:
  if callback():return
  time.sleep(1)
 raise RuntimeError('Local validation handshake timed out')
p=argparse.ArgumentParser();p.add_argument('--job');args=p.parse_args()
assert run(['config','current-context']).strip()=='k3d-knowledge-brain','Local context required'
if args.job:name=args.job
else:
 result=subprocess.run([sys.executable,str(root/'scripts/Run-NoteGraphApiValidation.py'),'--failures'],text=True,capture_output=True,check=True)
 name=re.search(r'JOB=(note-graph-api-validation-[0-9]+)',result.stdout).group(1)
if not re.fullmatch(r'note-graph-api-validation-[0-9]+',name):raise RuntimeError('Expected owned local browser validation Job')
job=json.loads(run(['get','job',name,'-n','brain-data','-o','json']));assert job['spec']['template']['metadata']['labels'].get('brain-guest-api-validation')=='true'
if job.get('status',{}).get('succeeded') or job.get('status',{}).get('failed'):raise RuntimeError('Validation Job must still be running before an outage test')
print('VALIDATION_JOB='+name,flush=True)
def status():return json.loads(run(['get','job',name,'-n','brain-data','-o','json']))['status']
def log_has(marker):
 if status().get('failed'):raise RuntimeError('Graph API validation Job failed; original replicas will be restored')
 result=subprocess.run(k+['logs','job/'+name,'-n','brain-data'],text=True,capture_output=True)
 return result.returncode==0 and marker in result.stdout
def marker(value):
 pod=json.loads(run(['get','pods','-n','brain-data','-l','job-name='+name,'-o','json']))['items'][0]['metadata']['name']
 run(['exec',pod,'-n','brain-data','--','python','-c',"from pathlib import Path;Path('/tmp/"+value+"').touch()"])
proof={'job':name,'replacements':[]}
for ns,resource,selector,phase,stage in [('brain-apps','deployment/space-service','app.kubernetes.io/name=space-service','AUTHORITY','authority'),('brain-data','statefulset/brain-cassandra','app=brain-cassandra','GRAPH','graph')]:
 wait_for(lambda:log_has(phase+'_OUTAGE_READY'))
 replicas=json.loads(run(['get',resource,'-n',ns,'-o','json']))['spec']['replicas'];assert replicas>0
 def pods():return json.loads(run(['get','pods','-n',ns,'-l',selector,'-o','json']))['items']
 before=[x['metadata']['uid'] for x in pods()]
 try:
  run(['scale',resource,'-n',ns,'--replicas=0']);wait_for(lambda:not pods(),90);marker(stage+'.down')
  wait_for(lambda:log_has(phase+'_OUTAGE_CONFIRMED'),45)
 finally:
  run(['scale',resource,'-n',ns,'--replicas='+str(replicas)])
  run(['rollout','status',resource,'-n',ns,'--timeout=120s'])
 after=[x['metadata']['uid'] for x in pods()];assert before and after and set(before).isdisjoint(after)
 proof['replacements'].append(dict(resource=resource,before=before,after=after,replicas=replicas));
 if stage=='graph':run(['rollout','status','deployment/brain-query-service','-n','brain-apps','--timeout=120s'])
 marker(stage+'.up')
 print('PASS '+resource+' unavailable response verified and actual Pod replaced',flush=True)
wait_for(lambda:status().get('succeeded')==1,120)
(root/'.local/note-graph-api-failure-proof.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')
for line in run(['logs','job/'+name,'-n','brain-data']).splitlines():
 if line.startswith('PASS'):print(line,flush=True)
print('PASS all original replica counts restored; browser graph fault validation complete',flush=True)
