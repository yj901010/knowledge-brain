"""Run Claim schema publication in the authorized local Kubernetes context."""
import json
import shutil
import subprocess
import time
from pathlib import Path

root = Path(__file__).resolve().parents[1]
kubectl = [
    shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe',
    '--kubeconfig', str(root / '.local/kubeconfig.yaml'),
    '--server=https://127.0.0.1:6550',
]


def run(args, body=None):
    result = subprocess.run(kubectl + args, input=body, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout


def apply(document):
    print(run(['apply', '-f', '-'], json.dumps(document)).strip())


if run(['config', 'current-context']).strip() != 'k3d-knowledge-brain':
    raise RuntimeError('Contract bootstrap is restricted to the local knowledge-brain context')
name = 'typed-claim-contract-validation-' + str(time.time_ns())
apply(dict(
    apiVersion='v1', kind='ConfigMap', metadata=dict(name=name, namespace='brain-data'),
    data={
        'verify.py': Path(__file__).with_name('Publish-TypedClaimContract.py').read_text(encoding='utf-8'),
        'knowledge_claim_v2.proto': (
            root / 'contracts/events/src/main/protobuf/knowledge_claim_v2.proto'
        ).read_bytes().decode('utf-8'),
    },
))
apply(dict(
    apiVersion='batch/v1', kind='Job', metadata=dict(name=name, namespace='brain-data'),
    spec=dict(
        backoffLimit=0, activeDeadlineSeconds=180, ttlSecondsAfterFinished=86400,
        template=dict(spec=dict(
            restartPolicy='Never', automountServiceAccountToken=False,
            securityContext=dict(runAsNonRoot=True, runAsUser=10001, runAsGroup=10001),
            containers=[dict(
                name='verify', image='python:3.13.7-alpine',
                command=['python', '/scripts/verify.py'],
                env=[dict(name='PYTHONDONTWRITEBYTECODE', value='1')],
                securityContext=dict(
                    allowPrivilegeEscalation=False, readOnlyRootFilesystem=True,
                    capabilities=dict(drop=['ALL']),
                ),
                resources=dict(
                    requests=dict(cpu='50m', memory='64Mi'),
                    limits=dict(cpu='250m', memory='128Mi'),
                ),
                volumeMounts=[
                    dict(name='scripts', mountPath='/scripts', readOnly=True),
                    dict(name='clients', mountPath='/clients', readOnly=True),
                    dict(name='trust', mountPath='/trust', readOnly=True),
                ],
            )],
            volumes=[
                dict(name='scripts', configMap=dict(name=name)),
                dict(name='clients', secret=dict(secretName='brain-registry-clients')),
                dict(name='trust', secret=dict(
                    secretName='brain-keycloak-tls', items=[dict(key='tls.crt', path='tls.crt')],
                )),
            ],
        )),
    ),
))
job = json.loads(run(['get', 'job', name, '-n', 'brain-data', '-o', 'json']))
run(['patch', 'configmap', name, '-n', 'brain-data', '--type=merge', '-p', json.dumps(dict(
    metadata=dict(ownerReferences=[dict(
        apiVersion='batch/v1', kind='Job', name=name, uid=job['metadata']['uid'],
    )]),
))])
print('JOB=' + name, flush=True)
