"""Fail-closed tests for destructive-target and authenticated-backup boundaries."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import tarfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('recovery', Path(__file__).parents[1] / 'local_recovery.py')
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)


class RecoveryTests(unittest.TestCase):
    def test_parallel_table_order_is_ignored_but_every_count_hash_and_database_remains(self):
        rows = ['public.one|2|'+'a'*32,'public.two|3|'+'b'*32]
        expected = r.canonical_fingerprints({'owner':rows})
        self.assertEqual(expected,r.canonical_fingerprints({'owner':list(reversed(rows))}))
        for actual in [{'owner':rows[:1]},{'owner':rows+rows[:1]},
                       {'owner':[rows[0],rows[1].replace('|3|','|4|')]},
                       {'owner':[rows[0],rows[1].replace('b'*32,'c'*32)]},
                       {'another-owner':rows}]:
            self.assertNotEqual(expected,r.canonical_fingerprints(actual))

    def test_image_graph_requires_all_runtime_layers_but_not_other_architectures(self):
        root, manifest, config, layer, arm = ['sha256:' + c * 64 for c in '01234']
        docs = {root:dict(manifests=[dict(digest=manifest,platform=dict(os='linux',architecture='amd64')),
                                     dict(digest=arm,platform=dict(os='linux',architecture='arm64'))]),
                manifest:dict(config=dict(digest=config),layers=[dict(digest=layer)])}
        self.assertEqual(r.required_image_content(root,docs.__getitem__),
                         [(root,'root'),(manifest,'manifest'),(config,'config'),(layer,'layer')])
        docs[root]['manifests'] = []
        with self.assertRaises(ValueError):r.required_image_content(root,docs.__getitem__)

    def test_missing_runtime_layer_stops_before_archive_or_freeze(self):
        root, config, layer = ['sha256:' + c * 64 for c in '012']
        def command(args):
            if args[-2:] == ['images','ls']:return ('REF TYPE DIGEST\nimage type '+root+'\n').encode()
            if args[-2:] == ['content','ls']:return ('DIGEST SIZE\n'+root+' 1\n'+config+' 1\n').encode()
            if args[-3:] == ['content','get',root]:return json.dumps(dict(config=dict(digest=config),layers=[dict(digest=layer)])).encode()
            raise AssertionError('Unexpected mutating/export command')
        with patch.object(r,'command',side_effect=command):
            with self.assertRaisesRegex(RuntimeError,'Required runtime image content is missing'):
                r.check_image_content(dict(images={'owned-node':['image']}))

    def test_optional_delivery_namespaces_are_backed_up_without_other_tenants(self):
        base = list(r.NAMESPACES)
        cluster = unittest.mock.Mock()
        cluster.get.return_value = dict(items=[dict(metadata=dict(name=n)) for n in
            base + ['brain-delivery', 'brain-delivery-probe', 'argocd', 'unrelated-private']])
        self.assertEqual(r.namespace_inventory(cluster), base + r.DELIVERY_NAMESPACES)
        cluster.get.return_value = dict(items=[dict(metadata=dict(name=n)) for n in base])
        self.assertEqual(r.namespace_inventory(cluster), base)
        cluster.get.return_value = dict(items=[])
        with self.assertRaises(RuntimeError):
            r.namespace_inventory(cluster)

    def test_cold_writer_check_includes_delivery_volumes(self):
        cluster = unittest.mock.Mock()
        cluster.get.side_effect = lambda kind, namespace: dict(items=[dict(metadata=dict(name='delivery-writer'),
            spec=dict(volumes=[dict(persistentVolumeClaim=dict(claimName='git-data'))]),status=dict(phase='Running'))]
            if namespace == 'brain-delivery' else [])
        with patch.object(r.time,'monotonic',side_effect=[0,0,3]),patch.object(r.time,'sleep'):
            with self.assertRaisesRegex(RuntimeError,'brain-delivery/delivery-writer'):
                r.wait_no_writers(cluster,timeout=2,namespaces=['brain-apps','brain-delivery'])

    def test_resume_refuses_a_ready_target_without_mutation(self):
        from argparse import Namespace
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = root / '.local/recovery-runs'
            folder.mkdir(parents=True)
            name = 'knowledge-brain-restore-abc123'
            key = root / 'key'
            key.write_bytes(r.base64.b64encode(b'k' * 32))
            (folder / (name + '-kubeconfig.yaml')).write_text('unchanged')
            (folder / (name + '.json')).write_text(json.dumps(dict(state='RESTORED', sourceUid='source', backup=str(root), volumeChecks=[])))
            args = Namespace(name=name, api_port=6650, key=key, backup=root, k3d='k3d', resume=True)
            with patch.object(r, 'ROOT', root), patch.object(r, 'verify_backup', return_value=dict(sourceUid='source',volumes=[])), patch.object(r, 'command', return_value=json.dumps([dict(name=name)]).encode()) as command, patch.object(r, 'Cluster') as cluster:
                with self.assertRaises(ValueError):
                    r.restore(args)
                cluster.assert_not_called()
                self.assertEqual(command.call_count, 1)  # read-only cluster inventory only

    def test_resume_refuses_a_changed_target_uid(self):
        from argparse import Namespace
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = root / '.local/recovery-runs'
            folder.mkdir(parents=True)
            name = 'knowledge-brain-restore-abc123'
            key = root / 'key'
            key.write_bytes(r.base64.b64encode(b'k' * 32))
            (folder / (name + '-kubeconfig.yaml')).write_text('unchanged')
            (folder / (name + '.json')).write_text(json.dumps(dict(state='RESTORING',sourceUid='source',targetUid='old-target',backup=str(root),volumeChecks=[])))
            args = Namespace(name=name,api_port=6650,key=key,backup=root,k3d='k3d',resume=True)
            with patch.object(r,'ROOT',root),patch.object(r,'verify_backup',return_value=dict(sourceUid='source',volumes=[])),patch.object(r,'command',return_value=json.dumps([dict(name=name)]).encode()) as command,patch.object(r,'Cluster') as cluster:
                cluster.return_value.uid.return_value = 'different-target'
                with self.assertRaises(ValueError):
                    r.restore(args)
                self.assertEqual(command.call_count,1)
                cluster.return_value.apply.assert_not_called()

    def test_source_kit_traversal_and_links_fail_before_destination_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            for index, (name, kind) in enumerate([('../escape', tarfile.REGTYPE), ('C:/escape', tarfile.REGTYPE), ('link', tarfile.SYMTYPE)]):
                archive = io.BytesIO()
                with tarfile.open(fileobj=archive, mode='w:gz') as tar:
                    for marker in ['AGENTS.md', 'PROJECT.md', 'scripts/local_recovery.py']:
                        tar.addfile(tarfile.TarInfo(marker))
                    member = tarfile.TarInfo(name)
                    member.type = kind
                    tar.addfile(member)
                path = Path(directory) / ('archive-' + str(index) + '.enc')
                writer = r.EncryptedWriter(path, b'k' * 32)
                writer.write(archive.getvalue())
                writer.finish()
                destination = Path(directory) / 'new-source'
                with self.assertRaisesRegex(ValueError, 'Unsafe source archive member'):
                    r.unpack_source(path, destination, b'k' * 32)
                self.assertFalse(destination.exists())

    def test_roundtrip_binary_and_nonce_uniqueness(self):
        with tempfile.TemporaryDirectory() as directory:
            key, payload = b'k' * 32, bytes(range(256)) * 8193
            outputs = []
            for name in ['one', 'two']:
                path = Path(directory) / name
                writer = r.EncryptedWriter(path, key)
                for offset in range(0, len(payload), 337):
                    writer.write(payload[offset:offset + 337])
                record = writer.finish()
                plain = io.BytesIO()
                result = r.decrypt(path, key, plain)
                self.assertEqual(plain.getvalue(), payload)
                self.assertEqual(result['sha256'], record['sha256'])
                outputs.append(path.read_bytes())
            self.assertNotEqual(outputs[0], outputs[1])

    def test_corruption_wrong_key_and_truncation_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'blob'
            writer = r.EncryptedWriter(path, b'k' * 32)
            writer.write(b'private data' * 400)
            writer.finish()
            original = path.read_bytes()
            for modified, key in [(original, b'x' * 32), (original[:-1], b'k' * 32),
                                  (original[:8], b'k' * 32),
                                  (original[:90] + bytes([original[90] ^ 1]) + original[91:], b'k' * 32)]:
                path.write_bytes(modified)
                with self.assertRaises(Exception):
                    r.decrypt(path, key)

    def test_existing_backup_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'blob'
            writer = r.EncryptedWriter(path, b'k' * 32)
            writer.write(b'first')
            # In-progress attempts cannot be silently replaced.
            with self.assertRaises(FileExistsError):
                r.EncryptedWriter(path, b'k' * 32)
            writer.abort()
            self.assertFalse(path.exists())
            self.assertFalse(path.with_suffix('.partial').exists())
            path.write_bytes(b'completed original')
            with self.assertRaises(FileExistsError):
                r.EncryptedWriter(path, b'k' * 32)
            self.assertEqual(path.read_bytes(), b'completed original')

    def test_source_and_ambiguous_restore_names_are_refused(self):
        for name in ['knowledge-brain', 'production', 'knowledge-brain-restore-x',
                     'knowledge-brain-restore-../../source', 'knowledge-brain-restore-a;rm']:
            with self.assertRaises(ValueError):
                r.validate_target(name, 6650)
        for port in [6550, 8443, 8444, 8445, 9443, 22, 70000]:
            with self.assertRaises(ValueError):
                r.validate_target('knowledge-brain-restore-abc123', port)
        r.validate_target('knowledge-brain-restore-abc123', 6650)

    def test_authentication_failure_prevents_all_cluster_commands(self):
        from argparse import Namespace
        with tempfile.TemporaryDirectory() as directory:
            key = Path(directory) / 'key'
            key.write_bytes(r.base64.b64encode(b'k' * 32))
            args = Namespace(name='knowledge-brain-restore-abc123', api_port=6650,
                             key=key, backup=directory, k3d='k3d')
            with patch.object(r, 'command') as command:
                with self.assertRaises(FileNotFoundError):
                    r.restore(args)
                command.assert_not_called()

    def test_incomplete_marker_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'backup.json').write_text(json.dumps({'state': 'PARTIAL', 'formatVersion': 1}))
            with self.assertRaises(ValueError):
                r.verify_backup(directory, b'k' * 32)

    def test_operator_resources_drop_old_uids_but_keep_ownership_and_tls(self):
        source = {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'brain-test',
            'namespace': 'brain-data', 'uid': 'old', 'ownerReferences': [{'uid': 'old'}],
            'managedFields': [], 'annotations': {'kubectl.kubernetes.io/last-applied-configuration': 'secret',
                                               'meta.helm.sh/release-name': 'brain-test'}},
            'data': {'tls.key': 'sealed'}, 'status': {}}
        clean = r.clean(source)
        self.assertNotIn('uid', clean['metadata'])
        self.assertNotIn('ownerReferences', clean['metadata'])
        self.assertNotIn('status', clean)
        self.assertEqual(clean['data'], source['data'])
        self.assertEqual(clean['metadata']['annotations'], {'meta.helm.sh/release-name': 'brain-test'})
        self.assertIn('uid', source['metadata'])


if __name__ == '__main__':
    unittest.main()
