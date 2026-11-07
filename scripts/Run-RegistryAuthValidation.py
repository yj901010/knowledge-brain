import json,subprocess,sys
from pathlib import Path
import shutil
k=[shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe','--kubeconfig',str(Path(__file__).resolve().parents[1]/'.local/kubeconfig.yaml'),'--server=https://127.0.0.1:6550']
def apply(doc):
 r=subprocess.run(k+['apply','-f','-'],input=json.dumps(doc),text=True,capture_output=True)
 if r.returncode: raise RuntimeError(r.stderr)
 print(r.stdout.strip())
apply(dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name='registry-auth-validation',namespace='brain-data'),data={'verify.py':Path(__file__).with_name('Verify-RegistryAuth.py').read_text(encoding='utf-8')}))
artifact=sys.argv[1] if len(sys.argv)>1 else None
name='registry-auth-restart-validation' if artifact else 'registry-auth-validation'
job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=name,namespace='brain-data'),spec=dict(backoffLimit=0,activeDeadlineSeconds=180,ttlSecondsAfterFinished=86400,template=dict(spec=dict(restartPolicy='Never',automountServiceAccountToken=False,securityContext=dict(runAsNonRoot=True,runAsUser=10001,runAsGroup=10001),containers=[dict(name='verify',image='python:3.13.7-alpine',command=['python','/scripts/verify.py'],env=[dict(name='PYTHONDONTWRITEBYTECODE',value='1')]+([dict(name='VERIFY_ARTIFACT',value=artifact)] if artifact else []),securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),resources=dict(requests=dict(cpu='50m',memory='64Mi'),limits=dict(cpu='250m',memory='128Mi')),volumeMounts=[dict(name='scripts',mountPath='/scripts',readOnly=True),dict(name='clients',mountPath='/clients',readOnly=True),dict(name='trust',mountPath='/trust',readOnly=True)])],volumes=[dict(name='scripts',configMap=dict(name='registry-auth-validation')),dict(name='clients',secret=dict(secretName='brain-registry-clients')),dict(name='trust',secret=dict(secretName='brain-keycloak-tls',items=[dict(key='tls.crt',path='tls.crt')]))]))))
apply(job)
print('JOB='+name)
