"""Create/verify persistent recovery fixtures without logging browser credentials."""
import argparse
import base64
import importlib.util
import json
import re
from pathlib import Path
import sys
import time

spec = importlib.util.spec_from_file_location('recovery', Path(__file__).with_name('local_recovery.py'))
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=['prepare', 'inspect', 'verify'], required=True)
    parser.add_argument('--kubeconfig', default=str(r.ROOT / '.local/kubeconfig.yaml'))
    parser.add_argument('--cluster', default='knowledge-brain')
    args = parser.parse_args()
    if args.cluster != r.SOURCE and not args.cluster.startswith('knowledge-brain-restore-'):
        raise ValueError('Only the explicit local source/restore contexts are allowed')
    cluster = r.Cluster(args.kubeconfig, args.cluster)
    name = 'recovery-' + args.mode + '-' + str(int(time.time()))
    data = {n: (r.ROOT / 'apps/graph-web' / n).read_text(encoding='utf-8') for n in ['package.json', 'package-lock.json', 'playwright.config.ts']}
    data['recovery.spec.ts'] = (r.ROOT / 'apps/graph-web/tests/recovery.spec.ts').read_text(encoding='utf-8')
    data['bridge.js'] = "const net=require('net');" + ''.join(
        "net.createServer(c=>{const s=net.connect(" + str(port) + ",'" + host + "');c.pipe(s);s.pipe(c);c.on('error',()=>s.destroy());s.on('error',()=>c.destroy())}).listen(" + str(local) + ",'127.0.0.1');"
        for local, host, port in [(8443, 'gateway-service.brain-apps.svc', 8080), (8445, 'brain-login.brain-apps.svc', 8443), (9443, 'brain-minio.brain-data.svc', 9000)])
    data['run.sh'] = '''set -eu
apt-get update -qq && apt-get install -y -qq libnss3-tools >/dev/null
mkdir -p /tmp/graph-home/.pki/nssdb /tmp/build/tests
export HOME=/tmp/graph-home NODE_EXTRA_CA_CERTS=/trust/ca.crt
certutil -N -d sql:$HOME/.pki/nssdb --empty-password
certutil -A -d sql:$HOME/.pki/nssdb -n brain-local -t 'C,,' -i /trust/ca.crt
cp /source/package*.json /source/playwright.config.ts /tmp/build/
cp /source/recovery.spec.ts /tmp/build/tests/
node /source/bridge.js &
cd /tmp/build
npm ci --no-audit --no-fund >/dev/null
npx playwright test recovery.spec.ts --workers=1
if [ "$BRAIN_RECOVERY_MODE" = prepare ]; then
 touch /tmp/recovery-prepared
 while [ ! -f /tmp/recovery-captured ]; do sleep 1; done
fi
'''
    cluster.apply([dict(apiVersion='v1', kind='ConfigMap', metadata=dict(name=name, namespace='brain-data'), data=data)])
    mounts = [dict(name='source', mountPath='/source', readOnly=True), dict(name='trust', mountPath='/trust', readOnly=True), dict(name='shm', mountPath='/dev/shm')]
    volumes = [dict(name='source', configMap=dict(name=name)), dict(name='trust', secret=dict(secretName='brain-guest-api-trust')), dict(name='shm', emptyDir=dict(medium='Memory', sizeLimit='256Mi'))]
    if args.mode != 'prepare':
        mounts.append(dict(name='fixture', mountPath='/fixture', readOnly=True))
        volumes.append(dict(name='fixture', secret=dict(secretName='brain-recovery-fixture')))
    job = dict(apiVersion='batch/v1', kind='Job', metadata=dict(name=name, namespace='brain-data'), spec=dict(backoffLimit=0, activeDeadlineSeconds=900, ttlSecondsAfterFinished=86400,
        template=dict(metadata=dict(labels={'brain-guest-api-validation': 'true'}), spec=dict(restartPolicy='Never', automountServiceAccountToken=False,
        containers=[dict(name='verify', image='mcr.microsoft.com/playwright:v1.64.0-noble', command=['bash', '/source/run.sh'], env=[dict(name='BRAIN_RECOVERY_MODE', value=args.mode)],
        securityContext=dict(allowPrivilegeEscalation=False), resources=dict(requests=dict(cpu='100m', memory='512Mi'), limits=dict(cpu='1500m', memory='1536Mi')), volumeMounts=mounts)], volumes=volumes))))
    cluster.apply([job])
    uid = cluster.get('job', name, 'brain-data')['metadata']['uid']
    cluster.run('patch', 'configmap', name, '-n', 'brain-data', '--type=merge', '-p', json.dumps({'metadata': {'ownerReferences': [{'apiVersion': 'batch/v1', 'kind': 'Job', 'name': name, 'uid': uid}]}}))
    print('JOB=' + name, flush=True)
    captured = args.mode != 'prepare'
    deadline = time.monotonic() + 920
    while time.monotonic() < deadline:
        status = cluster.get('job', name, 'brain-data').get('status', {})
        if status.get('failed'):
            log = cluster.run('logs', 'job/' + name, '-n', 'brain-data').decode()
            # Failure call logs can contain typed credentials. Keep the original
            # only in the private Job, never copy it to host/general output.
            raise RuntimeError('Recovery browser validation failed; inspect the retained private Job')
        if status.get('succeeded'):
            logs = cluster.run('logs', 'job/' + name, '-n', 'brain-data').decode()
            sentinel = {'prepare':'RECOVERY_PREPARED:','inspect':'RECOVERY_INSPECTED:',
                        'verify':'RECOVERY_RESTORED_VERIFIED:'}[args.mode]
            if sentinel not in logs or '1 passed' not in logs:
                raise RuntimeError('Recovery Job completed without the expected browser assertions')
            r.write_json(r.ROOT / '.local/recovery-runs' / (args.cluster + '-browser-' + args.mode + '.json'),
                         dict(mode=args.mode, job=name, uid=uid, clusterUid=cluster.uid(), verifiedAt=r.now(),
                              browserScenariosPassed=1, credentialContentsLogged=False))
            safe = [line for line in logs.splitlines() if line.startswith(sentinel) or
                    re.match(r'^\s*(?:[✓✘]|\d+ (?:passed|failed)|Running \d+ tests)',line)]
            print('\n'.join(safe), flush=True)
            print('PASS recovery ' + args.mode, flush=True)
            return
        if not captured:
            pods = cluster.get('pods', namespace='brain-data')['items']
            pods = [p for p in pods if p['metadata'].get('labels', {}).get('job-name') == name and p.get('status', {}).get('phase') == 'Running']
            if pods:
                pod = pods[0]['metadata']['name']
                try:
                    cluster.run('exec', pod, '-n', 'brain-data', '--', 'test', '-f', '/tmp/recovery-prepared')
                except RuntimeError:
                    time.sleep(3)
                    continue
                fixture = cluster.run('exec', pod, '-n', 'brain-data', '--', 'cat', '/tmp/recovery-fixture.json')
                json.loads(fixture)
                cluster.apply([dict(apiVersion='v1', kind='Secret', metadata=dict(name='brain-recovery-fixture', namespace='brain-data'), type='Opaque', data={'fixture.json': base64.b64encode(fixture).decode()})])
                cluster.run('exec', pod, '-n', 'brain-data', '--', 'touch', '/tmp/recovery-captured')
                captured = True
        time.sleep(3)
    raise TimeoutError('Recovery browser validation deadline exceeded')


if __name__ == '__main__':
    main()
