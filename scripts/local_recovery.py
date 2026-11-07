"""Cold, encrypted local k3d backup and clean-cluster restore. Operator only.

No source PVC is replaced. Restore always creates a new, isolated cluster.
Requires the existing host Python/cryptography, Docker, kubectl and k3d.
"""
from __future__ import annotations

import argparse
import base64
import copy
from contextlib import contextmanager
import datetime as dt
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import threading
import time

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

ROOT = Path(__file__).resolve().parents[1]
MAGIC = b'BRAIN-BACKUP-GCM-1\n'
CHUNK = 1024 * 1024
NAMESPACES = ['brain-apps', 'brain-data', 'brain-events', 'brain-ops', 'brain-observability']
DELIVERY_NAMESPACES = ['brain-delivery', 'brain-delivery-probe', 'argocd']
SOURCE = 'knowledge-brain'
KUBECTL = shutil.which('kubectl') or 'C:/Program Files/Docker/Docker/resources/bin/kubectl.exe'
CTR = ['/bin/ctr', '--address=/run/k3s/containerd/containerd.sock', '--namespace=k8s.io']
KINDS = ['serviceaccounts', 'roles', 'rolebindings', 'services', 'configmaps',
         'secrets', 'networkpolicies', 'deployments', 'statefulsets',
         'kafkas.kafka.strimzi.io', 'kafkanodepools.kafka.strimzi.io',
         'kafkatopics.kafka.strimzi.io', 'kafkausers.kafka.strimzi.io']


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.partial')
    temporary.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
    os.replace(temporary, path)


def command(args, body=None, timeout=120):
    result = subprocess.run([str(a) for a in args], input=body, capture_output=True, timeout=timeout)
    if result.returncode:
        # Do not echo stdin, credentials or API response bodies into general logs.
        raise RuntimeError('Command failed: ' + ' '.join(str(a) for a in args[:5]) +
                           '\n' + result.stderr.decode('utf-8', errors='replace')[:1800])
    return result.stdout


class Cluster:
    def __init__(self, kubeconfig, name):
        self.config = Path(kubeconfig).resolve()
        self.name = name
        self.prefix = [KUBECTL, '--kubeconfig', self.config, '--context', 'k3d-' + name,
                       '--request-timeout=30s']

    def run(self, *args, body=None, timeout=120):
        if isinstance(body, dict):
            body = json.dumps(body).encode()
        return command([*self.prefix, *args], body, timeout)

    def get(self, kind, name=None, namespace=None):
        args = ['get', kind]
        if name:
            args.append(name)
        if namespace:
            args += ['-n', namespace]
        return json.loads(self.run(*args, '-o', 'json'))

    def apply(self, documents):
        if not documents:
            return
        # Bound requests even when the source has many completed validation ConfigMaps.
        batch, size = [], 0
        for document in documents:
            length = len(json.dumps(document).encode())
            if batch and size + length > 1500000:
                self.run('apply', '--server-side', '--field-manager=brain-local-recovery', '-f', '-',
                         body={'apiVersion': 'v1', 'kind': 'List', 'items': batch}, timeout=180)
                batch, size = [], 0
            batch.append(document)
            size += length
        self.run('apply', '--server-side', '--field-manager=brain-local-recovery', '-f', '-',
                 body={'apiVersion': 'v1', 'kind': 'List', 'items': batch}, timeout=180)

    def scale(self, resource, count):
        self.run('scale', resource['kind'] + '/' + resource['metadata']['name'],
                 '-n', resource['metadata']['namespace'], '--replicas=' + str(count))

    def ready(self, kind, name, namespace, timeout=600):
        self.run('rollout', 'status', kind + '/' + name, '-n', namespace,
                 '--timeout=' + str(timeout) + 's', timeout=timeout + 20)

    def uid(self):
        return self.get('namespace', 'kube-system')['metadata']['uid']


def private_path(path, directory=False):
    """Restrict recovery keys/staging to the current OS user; never print key bytes."""
    path = Path(path)
    if directory:
        path.mkdir(parents=True, exist_ok=True)
    if os.name == 'nt':
        sid = json.loads(command(['powershell', '-NoProfile', '-Command',
            '[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value | ConvertTo-Json']).decode('utf-8-sig'))
        command(['icacls', path, '/inheritance:r', '/grant:r',
                 '*' + sid + (':(OI)(CI)F' if directory else ':F')])
    else:
        path.chmod(0o700 if directory else 0o600)


def load_key(path):
    key = base64.b64decode(Path(path).read_bytes().strip(), validate=True)
    if len(key) != 32:
        raise ValueError('A 32-byte base64 recovery key is required')
    return key


def key_id(key):
    return hashlib.sha256(key).hexdigest()[:24]


class EncryptedWriter:
    def __init__(self, path, key):
        self.path = Path(path)
        if self.path.exists():
            raise FileExistsError('Existing backup blobs are never overwritten')
        self.partial = self.path.with_suffix(self.path.suffix + '.partial')
        self.file = self.partial.open('xb')
        nonce = os.urandom(12)
        self.header = MAGIC + nonce
        self.file.write(self.header)
        self.cipher = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
        self.cipher.authenticate_additional_data(self.header)
        self.sha = hashlib.sha256()
        self.size = 0

    def write(self, data):
        self.size += len(data)
        if self.size > 2**36 - 32:
            raise ValueError('Blob exceeds the AES-GCM per-message limit')
        self.sha.update(data)
        self.file.write(self.cipher.update(data))
        return len(data)

    def flush(self):
        self.file.flush()

    def finish(self):
        self.file.write(self.cipher.finalize())
        self.file.write(self.cipher.tag)
        self.file.flush()
        os.fsync(self.file.fileno())
        self.file.close()
        os.replace(self.partial, self.path)
        return {'file': self.path.name, 'plaintextBytes': self.size,
                'sha256': self.sha.hexdigest(), 'encryptedBytes': self.path.stat().st_size}

    def abort(self):
        self.file.close()
        self.partial.unlink(missing_ok=True)


def decrypt(path, key, sink=None):
    """Verify GCM before any caller may *use* output. Restore verifies ALL blobs first."""
    path = Path(path)
    with path.open('rb') as source:
        header = source.read(len(MAGIC) + 12)
        if not header.startswith(MAGIC) or len(header) != len(MAGIC) + 12:
            raise ValueError('Invalid encrypted backup header')
        remaining = path.stat().st_size - len(header) - 16
        if remaining < 0 or remaining > 2**36 - 32:
            raise ValueError('Invalid encrypted backup size')
        source.seek(-16, 2)
        tag = source.read(16)
        source.seek(len(header))
        cipher = Cipher(algorithms.AES(key), modes.GCM(header[-12:], tag)).decryptor()
        cipher.authenticate_additional_data(header)
        sha, size = hashlib.sha256(), 0
        while remaining:
            data = source.read(min(CHUNK, remaining))
            if not data:
                raise ValueError('Truncated encrypted backup')
            plain = cipher.update(data)
            sha.update(plain)
            size += len(plain)
            if sink:
                sink.write(plain)
            remaining -= len(data)
        cipher.finalize()
        return {'sha256': sha.hexdigest(), 'plaintextBytes': size}


def encrypt_process(args, destination, key, compress=False, timeout=1800):
    writer = EncryptedWriter(destination, key)
    process = subprocess.Popen([str(a) for a in args], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    timer = threading.Timer(timeout, process.kill)
    timer.start()
    errors = []
    reader = threading.Thread(target=lambda: errors.append(process.stderr.read()), daemon=True)
    reader.start()
    output = gzip.GzipFile(fileobj=writer, mode='wb', compresslevel=1, mtime=0) if compress else writer
    try:
        while data := process.stdout.read(CHUNK):
            output.write(data)
        if compress:
            output.close()
        if process.wait() != 0:
            reader.join()
            raise RuntimeError('Backup stream failed: ' + errors[0].decode('utf-8', errors='replace')[:800])
        return writer.finish()
    except BaseException:
        process.kill()
        process.wait()
        writer.abort()
        raise
    finally:
        timer.cancel()
        process.stdout.close()
        process.stderr.close()


def clean(resource):
    resource = copy.deepcopy(resource)
    resource.pop('status', None)
    metadata = resource['metadata']
    for field in ['uid', 'resourceVersion', 'generation', 'creationTimestamp',
                  'managedFields', 'ownerReferences', 'finalizers', 'deletionTimestamp']:
        metadata.pop(field, None)
    annotations = metadata.get('annotations', {})
    for field in list(annotations):
        if field.startswith(('kubectl.kubernetes.io/', 'pv.kubernetes.io/', 'volume.kubernetes.io/')):
            annotations.pop(field)
    # Service clusterIPs intentionally preserved, including Cassandra's stable service address.
    return resource


def replica_inventory(resources):
    return [dict(kind=r['kind'], metadata={k: r['metadata'][k] for k in ['name', 'namespace']},
                 replicas=r['spec'].get('replicas', 1))
            for r in resources if r['kind'] in ['Deployment', 'StatefulSet']]


def namespace_inventory(cluster):
    present = {n['metadata']['name'] for n in cluster.get('namespaces')['items']}
    if not set(NAMESPACES).issubset(present):
        raise RuntimeError('Required platform namespaces are missing')
    return NAMESPACES + [n for n in DELIVERY_NAMESPACES if n in present]


def required_image_content(root, fetch):
    """Inspect the exact Linux/amd64 graph; never waive required runtime layers."""
    result = []
    def visit(descriptor, role):
        digest = descriptor['digest']
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
            raise ValueError('Unsupported image descriptor identity')
        result.append((digest, role))
        if role not in ['root', 'manifest']:
            return
        document = fetch(digest)
        if 'manifests' in document:
            matches = [d for d in document['manifests'] if d.get('platform', {}).get('os') == 'linux'
                       and d.get('platform', {}).get('architecture') == 'amd64']
            if len(matches) != 1:
                raise ValueError('One exact Linux/amd64 manifest is required')
            visit(matches[0], 'manifest')
        else:
            if 'config' not in document or not isinstance(document.get('layers'), list):
                raise ValueError('Incomplete runtime image manifest')
            visit(document['config'], 'config')
            for layer in document['layers']:
                visit(layer, 'layer')
    visit(dict(digest=root), 'root')
    return result


def check_image_content(inventory):
    failures = []
    for node, images in inventory['images'].items():
        prefix = ['docker', 'exec', node, *CTR]
        rows = command([*prefix, 'images', 'ls']).decode().splitlines()[1:]
        roots = {row.split()[0]: row.split()[2] for row in rows}
        present = {row.split()[0] for row in command([*prefix, 'content', 'ls']).decode().splitlines()[1:]}
        cache = {}
        def fetch(digest):
            if digest not in present:
                raise ValueError('Required image manifest is missing: ' + digest)
            if digest not in cache:
                cache[digest] = json.loads(command([*prefix, 'content', 'get', digest]))
            return cache[digest]
        for image in images:
            if image not in roots:
                raise ValueError('Exact selected image reference is unavailable')
            for digest, role in required_image_content(roots[image], fetch):
                if digest not in present:
                    failures.append(dict(node=node, image=image, digest=digest, role=role))
    if failures:
        raise RuntimeError('Required runtime image content is missing; no data freeze occurred: ' + json.dumps(failures))


def gather(cluster):
    resources = []
    namespaces = namespace_inventory(cluster)
    crds = cluster.get('customresourcedefinitions')['items']
    custom = [kind for kind in ['applications.argoproj.io', 'appprojects.argoproj.io',
                               'applicationsets.argoproj.io']
              if any(r['metadata']['name'] == kind for r in crds)]
    for namespace in namespaces:
        resources.append(cluster.get('namespace', namespace))
        for kind in KINDS + custom:
            for resource in cluster.get(kind, namespace=namespace)['items']:
                if resource['kind'] == 'ConfigMap' and resource['metadata']['name'] == 'kube-root-ca.crt':
                    continue
                if any(o['kind'] in ['Job', 'Pod'] for o in resource['metadata'].get('ownerReferences', [])):
                    continue
                if resource.get('type') == 'kubernetes.io/service-account-token':
                    continue
                resources.append(resource)
        if cluster.get('cronjobs', namespace=namespace)['items']:
            raise RuntimeError('Scheduled writers require an explicit freeze policy')
        if any(j.get('status', {}).get('active', 0) for j in cluster.get('jobs', namespace=namespace)['items']):
            raise RuntimeError('Finish active Jobs before starting a cold backup')
    resources += [r for r in crds if r['spec']['group'] in ['kafka.strimzi.io', 'core.strimzi.io', 'argoproj.io']]
    for kind in ['clusterroles', 'clusterrolebindings']:
        for resource in cluster.get(kind)['items']:
            if (resource['metadata']['name'].startswith(('strimzi', 'brain-headlamp', 'brain-local', 'brain-observability')) or
                any(s.get('namespace') in namespaces for s in resource.get('subjects', []))):
                resources.append(resource)
    # A namespaced binding can refer to a ClusterRole whose own name is unrelated
    # to our prefixes. Retain that exact role so restored grants are not dangling.
    role_names = {r['roleRef']['name'] for r in resources
                  if r['kind'] in ['RoleBinding', 'ClusterRoleBinding'] and r['roleRef']['kind'] == 'ClusterRole'}
    known_roles = {r['metadata']['name'] for r in resources if r['kind'] == 'ClusterRole'}
    for role in cluster.get('clusterroles')['items']:
        if role['metadata']['name'] in role_names - known_roles:
            resources.append(role)
    pvcs = [p for namespace in namespaces for p in cluster.get('pvc', namespace=namespace)['items']]
    volumes = []
    for pvc in pvcs:
        if pvc.get('status', {}).get('phase') != 'Bound':
            raise RuntimeError('All source PVCs must be Bound')
        pv = cluster.get('pv', pvc['spec']['volumeName'])
        local = pv['spec'].get('local', {}).get('path')
        terms = pv['spec'].get('nodeAffinity', {}).get('required', {}).get('nodeSelectorTerms', [])
        nodes = [v for term in terms for e in term.get('matchExpressions', [])
                 if e['key'] == 'kubernetes.io/hostname' and e['operator'] == 'In' for v in e['values']]
        if not local or not local.startswith('/var/lib/rancher/k3s/storage/pvc-') or len(nodes) != 1:
            raise RuntimeError('Only explicit single-node k3d local-path PVCs are supported')
        if not nodes[0].startswith('k3d-' + SOURCE + '-') or '..' in PurePosixPath(local).parts:
            raise RuntimeError('Source volume is outside the intended k3d cluster')
        volumes.append({'pvc': pvc, 'pv': pv, 'node': nodes[0], 'path': local})
    images = {}
    for namespace in namespaces:
        for pod in cluster.get('pods', namespace=namespace)['items']:
            if pod.get('status', {}).get('phase') != 'Running':
                continue
            for container in pod['spec'].get('initContainers', []) + pod['spec']['containers']:
                images.setdefault(pod['spec']['nodeName'], set()).add(container['image'])
    # Resolve short names exactly as containerd does; record all images once across nodes.
    selected, seen = {}, set()
    for node, names in images.items():
        available = command(['docker', 'exec', node, *CTR, 'images', 'list', '-q']).decode().splitlines()
        for name in sorted(names):
            if name in seen:
                continue
            normalized = name
            if '@' in name:
                repository, digest = name.split('@', 1)
                normalized = re.sub(r':[^/:]+$', '', repository) + '@' + digest
            candidates = {v for n in [name, normalized] for v in [n, 'docker.io/' + n, 'docker.io/library/' + n]}
            matches = [x for x in available if x in candidates]
            matches = list(dict.fromkeys(matches))
            if len(matches) != 1:
                raise RuntimeError('Cannot resolve exact running image: ' + name)
            selected.setdefault(node, []).append(matches[0])
            seen.add(name)
    return {'resources': resources, 'replicas': replica_inventory(resources),
            'volumes': volumes, 'images': selected, 'namespaces': namespaces,
            'sourceUid': cluster.uid(), 'capturedAt': now()}


def wait_no_writers(cluster, timeout=240, namespaces=None):
    started = time.monotonic()
    while time.monotonic() - started < timeout:
        writers = []
        for namespace in namespaces or NAMESPACES:
            for pod in cluster.get('pods', namespace=namespace)['items']:
                if pod.get('status', {}).get('phase') in ['Succeeded', 'Failed']:
                    continue
                if any('persistentVolumeClaim' in v for v in pod['spec'].get('volumes', [])):
                    writers.append(namespace + '/' + pod['metadata']['name'])
        if not writers:
            return
        time.sleep(3)
    raise RuntimeError('Source PVCs are still mounted by active Pods: ' + ', '.join(writers))


def canonical_fingerprints(value):
    # UNION ALL can return branch results in a different order under parallel
    # execution. Preserve every table/count/hash; order is not database content.
    return {database: sorted(rows) for database, rows in value.items()}


def pg_fingerprints(cluster):
    def sql(db, query):
        return cluster.run('exec', 'brain-postgres-0', '-n', 'brain-data', '--', 'psql',
                           '-U', 'postgres', '-d', db, '-v', 'ON_ERROR_STOP=1', '-At', '-c', query, timeout=180).decode().strip()
    databases = sql('postgres', "SELECT datname FROM pg_database WHERE NOT datistemplate ORDER BY datname").splitlines()
    result = {}
    for db in databases:
        tables = sql(db, "SELECT quote_ident(schemaname)||'.'||quote_ident(tablename) FROM pg_tables "
                        "WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY 1").splitlines()
        queries = ["SELECT '" + table + "' || '|' || count(*)::text || '|' || "
                   "md5(coalesce(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text),'')) FROM "
                   + table + ' t' for table in tables]
        result[db] = sql(db, ' UNION ALL '.join(queries)).splitlines() if queries else []
    return canonical_fingerprints(result)


def volume_fingerprint(node, path):
    # Paths originate in validated local-path PVs, never user-built shell fragments.
    if not re.fullmatch(r'/var/lib/rancher/k3s/storage/[a-zA-Z0-9_.-]+', path):
        raise ValueError('Invalid volume path')
    data = command(['docker', 'exec', node, 'sh', '-ec',
                    'cd ' + path + '; find . -type f -exec sha256sum {} \\;'], timeout=180)
    lines = sorted(data.splitlines())
    return {'files': len(lines), 'sha256': hashlib.sha256(b'\n'.join(lines)).hexdigest()}


def stop_source(cluster, inventory, journal, journal_path):
    journal['phase'] = 'quiescing'
    write_json(journal_path, journal)
    # Stop the reconciler before its workloads, even when an Application is
    # manual today. A future queued sync must not race the cold snapshot.
    for r in inventory['replicas']:
        if r['kind'] == 'StatefulSet' and r['metadata']['namespace'] in DELIVERY_NAMESPACES:
            cluster.scale(r, 0)
            cluster.ready('statefulset', r['metadata']['name'], r['metadata']['namespace'], 180)
    for r in inventory['replicas']:
        if r['metadata']['namespace'] == 'brain-apps':
            cluster.scale(r, 0)
    for r in inventory['replicas']:
        if r['kind'] == 'Deployment' and r['metadata']['namespace'] != 'brain-apps':
            cluster.scale(r, 0)
    # Wait for all writers/consumers to terminate before fingerprints and database shutdown.
    for namespace in ['brain-apps', 'brain-data', 'brain-events']:
        for r in inventory['replicas']:
            if r['kind'] == 'Deployment' and r['metadata']['namespace'] == namespace:
                cluster.ready('deployment', r['metadata']['name'], namespace, 180)
    deadline = time.monotonic() + 180
    while True:
        active = [p for ns in inventory.get('namespaces', NAMESPACES) for p in cluster.get('pods', namespace=ns)['items']
                  if p.get('status', {}).get('phase') not in ['Succeeded', 'Failed'] and
                  any(o['kind'] == 'ReplicaSet' for o in p['metadata'].get('ownerReferences', []))]
        if not active:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError('Application/CDC Pods did not finish graceful shutdown')
        time.sleep(3)
    inventory['postgresTables'] = pg_fingerprints(cluster)
    for r in inventory['replicas']:
        if r['kind'] == 'StatefulSet':
            cluster.scale(r, 0)
    for pod in cluster.get('pods', namespace='brain-events')['items']:
        if pod['metadata'].get('labels', {}).get('strimzi.io/kind') == 'Kafka':
            cluster.run('delete', 'pod', pod['metadata']['name'], '-n', 'brain-events', '--wait=true', timeout=180)
    wait_no_writers(cluster, namespaces=inventory.get('namespaces', NAMESPACES))
    journal['phase'] = 'cold'
    journal['consistencyAt'] = now()
    write_json(journal_path, journal)


def resume_source(cluster, journal):
    if cluster.uid() != journal['sourceUid']:
        raise RuntimeError('Source cluster UID changed; refusing replica restoration')
    replicas = journal['replicas']
    for r in replicas:
        if r['kind'] == 'StatefulSet' or r['metadata']['name'] in ['brain-minio', 'strimzi-cluster-operator']:
            cluster.scale(r, r['replicas'])
    for r in replicas:
        if r['kind'] == 'StatefulSet' and r['replicas']:
            cluster.ready('statefulset', r['metadata']['name'], r['metadata']['namespace'])
    if any(r['metadata']['name'] == 'strimzi-cluster-operator' and r['replicas'] for r in replicas):
        # Kafka Ready may still be the pre-shutdown condition. Wake existing PodSets
        # explicitly, then check actual recreated Pods rather than that stale status.
        for podset in cluster.get('strimzipodsets', namespace='brain-events')['items']:
            if podset['metadata']['name'].startswith('brain-kafka-'):
                cluster.run('annotate', 'strimzipodset/' + podset['metadata']['name'], '-n', 'brain-events',
                            'brain.knowledge/recovery-reconcile=' + str(time.time_ns()), '--overwrite')
        wait_kafka(cluster)
    for r in replicas:
        cluster.scale(r, r['replicas'])
    for r in replicas:
        if r['replicas']:
            cluster.ready(r['kind'].lower(), r['metadata']['name'], r['metadata']['namespace'])


def wait_kafka(cluster, timeout=600):
    expected = sum(p['spec']['replicas'] for p in cluster.get('kafkanodepools', namespace='brain-events')['items'])
    if not expected:
        raise RuntimeError('No Kafka nodes are defined')
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        pods = [p for p in cluster.get('pods', namespace='brain-events')['items']
                if p['metadata'].get('labels', {}).get('strimzi.io/component-type') == 'kafka'
                and not p['metadata'].get('deletionTimestamp')]
        if len(pods) == expected and all(any(c['type'] == 'Ready' and c['status'] == 'True'
                                           for c in p.get('status', {}).get('conditions', [])) for p in pods):
            cluster.run('wait', 'kafka/brain-kafka', '-n', 'brain-events', '--for=condition=Ready', '--timeout=120s', timeout=140)
            return
        time.sleep(3)
    raise TimeoutError('Actual Kafka broker/controller Pods did not become Ready')


def source_archive(destination, key):
    if not (ROOT / 'AGENTS.md').is_file() or not (ROOT / 'PROJECT.md').is_file():
        raise ValueError('Source export requires the actual project root')
    writer = EncryptedWriter(destination, key)
    try:
        with tarfile.open(fileobj=writer, mode='w|gz') as archive:
            for path in sorted(ROOT.rglob('*')):
                parts = path.relative_to(ROOT).parts
                if any(x in {'.git', '.local', 'work', 'node_modules', 'target', '.next', 'out', '__pycache__'} for x in parts):
                    continue
                if path.is_file() and path.name not in ['.env'] and not path.name.startswith('.env.'):
                    archive.add(path, arcname=path.relative_to(ROOT).as_posix(), recursive=False)
        return writer.finish()
    except BaseException:
        writer.abort()
        raise


def unpack_source(archive_path, destination, key):
    destination = Path(destination).resolve()
    if destination.exists():
        raise FileExistsError('Source extraction requires a new directory')
    if Path(archive_path).stat().st_size > 64 * 1024 * 1024:
        raise ValueError('Source kit exceeds the bounded64MiB archive size')
    verified = io.BytesIO()
    decrypt(archive_path, key, verified)
    verified.seek(0)
    with tarfile.open(fileobj=verified, mode='r:gz') as archive:
        members = archive.getmembers()
        if len(members) > 20000 or sum(m.size for m in members) > 1024**3:
            raise ValueError('Source kit extraction budget exceeded')
        names = {m.name for m in members}
        if not {'AGENTS.md', 'PROJECT.md', 'scripts/local_recovery.py'}.issubset(names):
            raise ValueError('Archive is not a project source recovery kit')
        for member in members:
            parts = PurePosixPath(member.name).parts
            if (not member.isfile() or not parts or PurePosixPath(member.name).is_absolute()
                or '..' in parts or '\\' in member.name or ':' in member.name):
                raise ValueError('Unsafe source archive member')
        private_path(destination, directory=True)
        for member in members:
            target = destination.joinpath(*PurePosixPath(member.name).parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as source, target.open('xb') as output:
                shutil.copyfileobj(source, output, CHUNK)
    return len(members)


def backup(args):
    key = load_key(args.key)
    output = Path(args.output).resolve()
    if output.exists():
        raise ValueError('Backup destination must be new; existing backups are never overwritten')
    # Host directory is outside the Docker VM and private, independent of repository state.
    cluster = Cluster(args.kubeconfig, SOURCE)
    inventory = gather(cluster)
    check_image_content(inventory)
    private_path(output, directory=True)
    if any(r['kind'] == 'CronJob' for r in inventory['resources']):
        raise RuntimeError('Scheduled writers require an explicit freeze policy')
    journal_path = ROOT / '.local/recovery-runs' / (output.name + '.json')
    journal = {'version': 1, 'sourceUid': inventory['sourceUid'], 'sourceKubeconfig': str(cluster.config),
               'backup': str(output), 'replicas': inventory['replicas'], 'startedAt': now(), 'phase': 'preparing'}
    write_json(journal_path, journal)
    blobs = []
    completed = False
    try:
        # Images/source can be captured while applications remain available.
        for index, (node, images) in enumerate(inventory['images'].items()):
            print('Archiving pinned images from ' + node, flush=True)
            item = encrypt_process(['docker', 'exec', node, *CTR, 'images', 'export', '--local', '--skip-manifest-json', '--platform', 'linux/amd64', '-', *images],
                                   output / ('images-' + str(index) + '.tar.gz.enc'), key, compress=True)
            item.update(kind='images', images=images)
            blobs.append(item)
        blobs.append(dict(source_archive(output / 'source.tar.gz.enc', key), kind='source'))
        print('Quiescing applications and data stores for a consistent backup', flush=True)
        stop_source(cluster, inventory, journal, journal_path)
        inventory['consistencyAt'] = journal['consistencyAt']
        for index, volume in enumerate(inventory['volumes']):
            wait_no_writers(cluster, namespaces=inventory.get('namespaces', NAMESPACES))
            volume['fingerprint'] = volume_fingerprint(volume['node'], volume['path'])
            item = encrypt_process(['docker', 'exec', volume['node'], 'tar', '-czf', '-', '-C', volume['path'], '.'],
                                   output / ('volume-' + str(index) + '.tar.gz.enc'), key)
            item.update(kind='volume', index=index)
            blobs.append(item)
            print('Backed up ' + volume['pvc']['metadata']['name'], flush=True)
        inventory['blobs'] = blobs
        inventory['formatVersion'] = 1
        inventory['k3sImage'] = command(['docker', 'inspect', 'k3d-knowledge-brain-server-0',
                                       '--format', '{{.Config.Image}}']).decode().strip()
        writer = EncryptedWriter(output / 'manifest.json.enc', key)
        writer.write(json.dumps(inventory).encode())
        manifest = writer.finish()
        # A COMPLETE marker appears only after authenticating every archived byte.
        for item in [manifest, *blobs]:
            actual = decrypt(output / item['file'], key)
            if any(actual[k] != item[k] for k in ['sha256', 'plaintextBytes']):
                raise RuntimeError('Backup read-back mismatch')
        write_json(output / 'backup.json', {'formatVersion': 1, 'state': 'COMPLETE',
            'createdAt': now(), 'consistencyAt': journal['consistencyAt'], 'keyId': key_id(key),
            'sourceUid': inventory['sourceUid'], 'manifest': manifest,
            'encryptedBytes': sum((output / i['file']).stat().st_size for i in [manifest, *blobs])})
        completed = True
        print('Backup authenticated and COMPLETE: ' + str(output), flush=True)
    finally:
        print('Restoring original source replica counts', flush=True)
        resume_source(cluster, journal)
        journal.update(phase='source-ready', backupComplete=completed, sourceResumedAt=now())
        write_json(journal_path, journal)


@contextmanager
def source_lock():
    path = ROOT / '.local/recovery-source.lock'
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a+b') as stream:
        stream.seek(0, 2)
        if not stream.tell():
            stream.write(b'0')
            stream.flush()
        stream.seek(0)
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == 'nt':
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream, fcntl.LOCK_UN)


def verify_backup(directory, key):
    directory = Path(directory).resolve()
    public = json.loads((directory / 'backup.json').read_text(encoding='utf-8'))
    if public.get('state') != 'COMPLETE' or public.get('formatVersion') != 1 or public.get('keyId') != key_id(key):
        raise ValueError('Incomplete backup, unsupported format or incorrect recovery key')
    buffer = io.BytesIO()
    actual = decrypt(directory / 'manifest.json.enc', key, buffer)
    if any(actual[k] != public['manifest'][k] for k in ['sha256', 'plaintextBytes']):
        raise ValueError('Manifest digest mismatch')
    inventory = json.loads(buffer.getvalue())
    if inventory.get('formatVersion') != 1 or inventory['sourceUid'] != public['sourceUid']:
        raise ValueError('Manifest identity mismatch')
    for item in inventory['blobs']:
        if not re.fullmatch(r'(images-\d+|volume-\d+|source)\.tar\.gz\.enc', item['file']):
            raise ValueError('Unsafe blob filename')
        actual = decrypt(directory / item['file'], key)
        if any(actual[k] != item[k] for k in ['sha256', 'plaintextBytes']):
            raise ValueError('Blob digest mismatch: ' + item['file'])
    return inventory


def validate_target(name, api_port):
    if not re.fullmatch(r'knowledge-brain-restore-[a-z0-9]{6,20}', name):
        raise ValueError('Restore requires a new knowledge-brain-restore-<6..20 chars> cluster')
    if api_port in [6550, 8088, 8443, 8444, 8445, 9443] or not 1024 <= api_port <= 65535:
        raise ValueError('Restore API port must be isolated from the source')


def pipe_blob(directory, item, key, args):
    """Only called after a full authenticated read-back of the complete backup."""
    process = subprocess.Popen([str(a) for a in args], stdin=subprocess.PIPE,
                               stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    errors = []
    reader = threading.Thread(target=lambda: errors.append(process.stderr.read()), daemon=True)
    reader.start()
    try:
        decrypt(Path(directory) / item['file'], key, process.stdin)
        process.stdin.close()
        if process.wait(timeout=600) != 0:
            reader.join()
            raise RuntimeError('Restore stream failed: ' + errors[0].decode('utf-8', errors='replace')[:1000])
    except BaseException:
        process.kill()
        process.wait()
        raise
    finally:
        process.stderr.close()


def restore(args):
    validate_target(args.name, args.api_port)
    key = load_key(args.key)
    print('Authenticating the complete backup before any target mutation', flush=True)
    inventory = verify_backup(args.backup, key)
    clusters = json.loads(command([args.k3d, 'cluster', 'list', '-o', 'json']))
    config = ROOT / '.local/recovery-runs' / (args.name + '-kubeconfig.yaml')
    report_path = ROOT / '.local/recovery-runs' / (args.name + '.json')
    resuming = getattr(args, 'resume', False)
    exists = any(c['name'] == args.name for c in clusters)
    if exists and not resuming:
        raise ValueError('Target cluster already exists; restore never overwrites a cluster')
    if resuming:
        if not exists or not report_path.is_file() or not config.is_file():
            raise ValueError('Frozen restore journal and existing target are required')
        report = json.loads(report_path.read_text(encoding='utf-8'))
        if (report['state'] != 'RESTORING' or report['sourceUid'] != inventory['sourceUid'] or
            report['backup'] != str(Path(args.backup).resolve()) or
            len(report['volumeChecks']) != len(inventory['volumes'])):
            raise ValueError('Only the same fully copied, still-frozen target can resume')
    else:
        report = {'state': 'RESTORING', 'startedAt': now(), 'name': args.name,
              'backup': str(Path(args.backup).resolve()), 'sourceUid': inventory['sourceUid'],
              'kubeconfig': str(config), 'volumeChecks': []}
        write_json(report_path, report)
        command([args.k3d, 'cluster', 'create', args.name, '--servers', '1', '--agents', '0',
                 '--image', inventory['k3sImage'], '--api-port', '127.0.0.1:' + str(args.api_port),
                 '--kubeconfig-update-default=false', '--kubeconfig-switch-context=false', '--wait'], timeout=300)
        config.write_bytes(command([args.k3d, 'kubeconfig', 'get', args.name]))
        private_path(config)
    cluster = Cluster(config, args.name)
    if resuming and cluster.uid() != report['targetUid']:
        raise ValueError('Frozen target UID changed')
    report['targetUid'] = cluster.uid()
    if report['targetUid'] == inventory['sourceUid']:
        raise RuntimeError('Target is not isolated from the source')
    write_json(report_path, report)
    node = 'k3d-' + args.name + '-server-0'
    command(['docker', 'exec', node, 'mkdir', '-p', '/var/lib/rancher/k3s/storage'])
    for item in inventory['blobs']:
        if item['kind'] == 'images' and not resuming:
            print('Importing ' + item['file'], flush=True)
            # Decompress in the target node; no plaintext host archive is written.
            pipe_blob(args.backup, item, key, ['docker', 'exec', '-i', node, 'sh', '-ec',
                'set -o pipefail; gzip -dc | /bin/ctr --address=/run/k3s/containerd/containerd.sock --namespace=k8s.io images import --local --platform linux/amd64 -'])
    resources = [clean(r) for r in inventory['resources']
                 if not (r['kind'] == 'ConfigMap' and r['metadata']['name'] == 'kube-root-ca.crt')]
    cluster.apply([r for r in resources if r['kind'] == 'CustomResourceDefinition'])
    cluster.run('wait', '--for=condition=Established',
                *['crd/' + r['metadata']['name'] for r in resources if r['kind'] == 'CustomResourceDefinition'],
                '--timeout=120s', timeout=140)
    cluster.apply([r for r in resources if r['kind'] == 'Namespace'])
    # Install retained static volumes before any workload can run.
    for index, volume in enumerate(inventory['volumes']):
        path = '/var/lib/rancher/k3s/storage/brain-restore-' + str(index)
        if resuming:
            if volume_fingerprint(node, path) != volume['fingerprint']:
                raise ValueError('Target data changed after copying; frozen restore cannot resume')
            pvc = cluster.get('pvc', volume['pvc']['metadata']['name'], volume['pvc']['metadata']['namespace'])
            pv = cluster.get('pv', 'brain-restore-' + str(index))
            if pvc['spec']['volumeName'] != pv['metadata']['name'] or pv['spec'].get('local', {}).get('path') != path:
                raise ValueError('Frozen target volume binding changed')
            print('Reverified unchanged frozen ' + pvc['metadata']['name'], flush=True)
            continue
        command(['docker', 'exec', node, 'sh', '-ec', 'test ! -e ' + path + '; mkdir -m 700 ' + path])
        item = next(b for b in inventory['blobs'] if b['kind'] == 'volume' and b['index'] == index)
        pipe_blob(args.backup, item, key, ['docker', 'exec', '-i', node, 'tar', '-xzf', '-', '-C', path])
        fingerprint = volume_fingerprint(node, path)
        if fingerprint != volume['fingerprint']:
            raise RuntimeError('Restored volume checksum mismatch: ' + volume['pvc']['metadata']['name'])
        pvc = clean(volume['pvc'])
        pv_name = 'brain-restore-' + str(index)
        pvc['spec']['volumeName'] = pv_name
        pv = {'apiVersion': 'v1', 'kind': 'PersistentVolume', 'metadata': {'name': pv_name},
              'spec': {'capacity': volume['pv']['spec']['capacity'], 'accessModes': ['ReadWriteOnce'],
                       'persistentVolumeReclaimPolicy': 'Retain', 'storageClassName': pvc['spec'].get('storageClassName', ''),
                       'local': {'path': path},
                       'claimRef': {'namespace': pvc['metadata']['namespace'], 'name': pvc['metadata']['name']},
                       'nodeAffinity': {'required': {'nodeSelectorTerms': [{'matchExpressions': [{
                           'key': 'kubernetes.io/hostname', 'operator': 'In', 'values': [node]}]}]}}}}
        cluster.apply([pv, pvc])
        report['volumeChecks'].append({'name': pvc['metadata']['name'], **fingerprint})
        write_json(report_path, report)
        print('Restored and byte-verified ' + pvc['metadata']['name'], flush=True)
    for r in resources:
        if r['kind'] in ['Deployment', 'StatefulSet']:
            r['spec']['replicas'] = 0
        if r['kind'] == 'Kafka':
            r['metadata'].setdefault('annotations', {})['strimzi.io/pause-reconciliation'] = 'true'
    cluster.apply([r for r in resources if r['kind'] not in ['CustomResourceDefinition', 'Namespace']])
    # Preserve Kafka cluster and node IDs before starting reconciliation over the restored logs.
    for r in inventory['resources']:
        if r['kind'] in ['Kafka', 'KafkaNodePool']:
            status = {k: v for k, v in r.get('status', {}).items() if k in ['clusterId', 'nodeIds']}
            if not status.get('clusterId'):
                raise RuntimeError('Missing original Kafka cluster ID')
            cluster.run('patch', r['kind'].lower(), r['metadata']['name'], '-n', 'brain-events',
                        '--subresource=status', '--type=merge', '-p', json.dumps({'status': status}))
    pg = next(r for r in inventory['replicas'] if r['metadata']['name'] == 'brain-postgres')
    cluster.scale(pg, 1)
    cluster.ready('statefulset', 'brain-postgres', 'brain-data')
    actual = pg_fingerprints(cluster)
    if actual != canonical_fingerprints(inventory['postgresTables']):
        raise RuntimeError('Restored PostgreSQL table fingerprints differ')
    report.update(postgresDatabases=len(actual), postgresTables=sum(map(len, actual.values())),
                  postgresTableFingerprintsMatch=True)
    write_json(report_path, report)
    print('Every PostgreSQL database/table fingerprint matches before application start', flush=True)
    cluster.run('annotate', 'kafka/brain-kafka', '-n', 'brain-events', 'strimzi.io/pause-reconciliation-')
    for r in inventory['replicas']:
        if r['kind'] == 'StatefulSet' or r['metadata']['name'] in ['brain-minio', 'strimzi-cluster-operator']:
            cluster.scale(r, r['replicas'])
    for r in inventory['replicas']:
        if r['kind'] == 'StatefulSet' and r['replicas']:
            cluster.ready('statefulset', r['metadata']['name'], r['metadata']['namespace'])
    wait_kafka(cluster)
    for r in inventory['replicas']:
        cluster.scale(r, r['replicas'])
    for r in inventory['replicas']:
        if r['replicas']:
            cluster.ready(r['kind'].lower(), r['metadata']['name'], r['metadata']['namespace'])
    report.update(state='RESTORED', restoredAt=now(), kafkaClusterId=cluster.get('kafka', 'brain-kafka', 'brain-events')['status']['clusterId'])
    write_json(report_path, report)
    print('Isolated restore is Ready. Evidence: ' + str(report_path), flush=True)


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    keygen = sub.add_parser('keygen')
    keygen.add_argument('--key', required=True)
    create = sub.add_parser('backup')
    create.add_argument('--key', required=True)
    create.add_argument('--output', required=True)
    create.add_argument('--kubeconfig', default=str(ROOT / '.local/kubeconfig.yaml'))
    check = sub.add_parser('verify')
    check.add_argument('--key', required=True)
    check.add_argument('--backup', required=True)
    recover = sub.add_parser('restore')
    recover.add_argument('--key', required=True)
    recover.add_argument('--backup', required=True)
    recover.add_argument('--name', required=True)
    recover.add_argument('--api-port', required=True, type=int)
    recover.add_argument('--k3d', default='k3d')
    recover.add_argument('--resume', action='store_true', help='Resume only a journal-owned fully copied, unchanged frozen target')
    resume = sub.add_parser('resume-source')
    resume.add_argument('--journal', required=True)
    kit = sub.add_parser('export-kit')
    kit.add_argument('--key', required=True)
    kit.add_argument('--output', required=True)
    unpack = sub.add_parser('unpack-source')
    unpack.add_argument('--key', required=True)
    unpack.add_argument('--archive', required=True)
    unpack.add_argument('--destination', required=True)
    args = parser.parse_args()
    if args.action == 'keygen':
        path = Path(args.key).resolve()
        private_path(path.parent, directory=True)
        with path.open('xb') as stream:
            stream.write(base64.b64encode(os.urandom(32)) + b'\n')
        private_path(path)
        print('Recovery key created (contents not displayed): ' + str(path))
    elif args.action == 'backup':
        with source_lock():
            backup(args)
    elif args.action == 'verify':
        inventory = verify_backup(args.backup, load_key(args.key))
        print('Authenticated complete backup: ' + str(len(inventory['volumes'])) + ' volumes')
    elif args.action == 'restore':
        restore(args)
    elif args.action == 'resume-source':
        journal = json.loads(Path(args.journal).read_text(encoding='utf-8'))
        with source_lock():
            resume_source(Cluster(journal['sourceKubeconfig'], SOURCE), journal)
            journal.update(phase='source-ready', sourceResumedAt=now())
            write_json(args.journal, journal)
    elif args.action == 'export-kit':
        key = load_key(args.key)
        record = source_archive(args.output, key)
        actual = decrypt(args.output, key)
        if any(actual[k] != record[k] for k in ['sha256', 'plaintextBytes']):
            raise RuntimeError('Recovery kit read-back mismatch')
        record.update(kind='recovery-source-kit', createdAt=now(), keyId=key_id(key))
        write_json(Path(args.output).with_suffix('.json'), record)
        print('Current source recovery kit authenticated: ' + str(args.output))
    elif args.action == 'unpack-source':
        count = unpack_source(args.archive, args.destination, load_key(args.key))
        print('Verified source kit extracted: ' + str(count) + ' files')


if __name__ == '__main__':
    main()
