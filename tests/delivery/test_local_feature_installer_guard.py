"""Isolated compatibility checks; never invoke a real builder, Helm or Kubernetes."""
import ast
from contextlib import redirect_stderr, redirect_stdout
import importlib
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

import yaml

SCRIPTS = Path(__file__).resolve().parents[2] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
guard = importlib.import_module('local_feature_installer_guard')


class HistoricalInstallerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.values = self.root / 'infra/environments/local'
        self.values.mkdir(parents=True)
        self.services = set().union(*guard.INSTALLER_SERVICES.values()) | {
            'activity', 'media', 'notification', 'planning',
        }
        for service in self.services:
            self.image(service, {'repository': 'knowledge-brain/' + service + '-service',
                                 'tag': 'historical-local-tag'})

    def image(self, service, value):
        path = self.values / (service + '-values.yaml')
        path.write_text(yaml.safe_dump({'image': value}), encoding='utf-8')
        return path

    def pinned(self):
        for service in self.services:
            self.image(service, {'repository': 'knowledge-brain/' + service + '-service',
                                 'tag': '', 'digest': 'sha256:' + 'a' * 64})

    def run_entrypoint(self, name, flags=(), refusal='digest-pinned application values'):
        # Execute each real entry point with a fake repository root and real guard.
        # Imports of the recovery helper cannot construct even a fake Cluster first.
        source = (SCRIPTS / name).read_text(encoding='utf-8')
        fake = self.root / 'scripts' / name
        recovery = types.ModuleType('local_recovery')
        recovery.ROOT = self.root
        recovery.Cluster = lambda *a, **k: self.fail('Cluster reached before guard')
        recovery.now = lambda: self.fail('Recovery work reached before guard')
        recovery.write_json = lambda *a, **k: self.fail('Evidence write reached before guard')
        with patch.dict(sys.modules, {'local_recovery': recovery}), \
                patch.object(sys, 'argv', [str(fake), '--helm', 'unused-helm', *flags]), \
                patch.object(subprocess, 'run', side_effect=AssertionError('command before guard')) as run, \
                patch.object(subprocess, 'Popen', side_effect=AssertionError('process before guard')) as popen, \
                patch.object(os, 'system', side_effect=AssertionError('shell before guard')) as shell, \
                patch.object(Path, 'write_text', side_effect=AssertionError('write before guard')) as text, \
                patch.object(Path, 'write_bytes', side_effect=AssertionError('write before guard')) as binary, \
                patch.object(Path, 'mkdir', side_effect=AssertionError('mkdir before guard')) as mkdir, \
                patch.object(Path, 'unlink', side_effect=AssertionError('unlink before guard')) as unlink:
            with self.assertRaisesRegex(SystemExit, refusal):
                exec(compile(source, str(fake), 'exec'), {'__name__': '__main__', '__file__': str(fake)})
            for effect in (run, popen, shell, text, binary, mkdir, unlink):
                effect.assert_not_called()

    def test_every_inventoried_entrypoint_stops_before_commands_builds_provisioning_or_writes(self):
        self.pinned()
        for name in guard.INSTALLER_SERVICES:
            with self.subTest(installer=name):
                self.run_entrypoint(name)

    def test_selector_branches_also_stop_before_side_effects(self):
        self.pinned()
        for flag in ('--brain-query-only', '--notification-only', '--planning-only',
                     '--gateway', '--activity-only', '--space-only', '--media-only',
                     '--knowledge-only', '--content-only', '--learning-only'):
            with self.subTest(flag=flag):
                self.run_entrypoint('Install-LocalRoadmaps.py', [flag])
        for flags in (['--gateway'], ['--enable'], ['--gateway', '--enable']):
            with self.subTest(flags=flags):
                self.run_entrypoint('Install-LocalBrainNetwork.py', flags)
        self.run_entrypoint('Install-LocalClaimUsages.py', ['--gateway-only'])

    def test_later_incompatible_service_stops_before_any_first_service_or_transport_work(self):
        self.pinned()
        self.image('space', {'tag': 'historical-local-tag'})
        self.run_entrypoint('Install-LocalNoteConcepts.py')
        self.run_entrypoint('Install-LocalPublicRoadmaps.py')
        self.image('knowledge', {'tag': 'historical-local-tag'})
        self.image('content', {'tag': 'old', 'digest': 42})
        self.run_entrypoint('Install-LocalNoteConcepts.py', refusal='invalid affected image values')

    def test_inventory_matches_direct_tag_writers_overrides_and_provisioning_wrappers(self):
        discovered = set()
        for path in SCRIPTS.glob('Install-Local*.py'):
            tree = ast.parse(path.read_text(encoding='utf-8'))
            for node in ast.walk(tree):
                if (isinstance(node, ast.Subscript) and isinstance(node.ctx, ast.Store)
                        and isinstance(node.slice, ast.Constant) and node.slice.value == 'tag'
                        and isinstance(node.value, ast.Subscript)
                        and isinstance(node.value.slice, ast.Constant)
                        and node.value.slice.value == 'image'):
                    discovered.add(path.name)
                if (isinstance(node, ast.Constant) and isinstance(node.value, str)
                        and (node.value.startswith('image.tag=')
                             or node.value == 'scripts/Install-LocalRoadmaps.py')):
                    discovered.add(path.name)
        self.assertEqual(set(guard.INSTALLER_SERVICES), discovered)
        for name in ('Cassandra', 'Delivery', 'Headlamp', 'Observability', 'OpenSearch'):
            source = (SCRIPTS / ('Install-Local' + name + '.py')).read_text(encoding='utf-8')
            self.assertNotIn('local_feature_installer_guard', source)

    def test_tag_only_and_explicit_empty_digest_preserve_historical_compatibility(self):
        for name in guard.INSTALLER_SERVICES:
            with self.subTest(installer=name):
                self.assertIsNone(guard.require_tag_baseline(name, self.root))
        for image in ({'tag': 'old'}, {'tag': 'old', 'digest': ''}, {}):
            self.image('gateway', image)  # repository/tag can come from base Helm values
            self.assertIsNone(guard.require_tag_baseline('Install-LocalGraphEditor.py', self.root))

    def test_unrelated_values_and_unselected_services_are_never_read(self):
        self.pinned()
        self.image('gateway', {'tag': 'historical-local-tag'})
        (self.values / 'headlamp-values.yaml').write_text('invalid: [', encoding='utf-8')
        (self.values / 'unrelated-private-values.yaml').write_text('invalid: [', encoding='utf-8')
        for name, options in (
                ('Install-LocalGraphEditor.py', {}),
                ('Install-LocalRoadmaps.py', {'gateway': True}),
                ('Install-LocalClaimUsages.py', {'gateway_only': True}),
                ('Install-LocalBrainNetwork.py', {'gateway': True, 'enable': True})):
            reads = []
            original = Path.read_bytes
            with patch.object(Path, 'read_bytes', lambda path: (reads.append(path), original(path))[1]):
                guard.require_tag_baseline(name, self.root, options)
            self.assertEqual(reads, [self.values / 'gateway-values.yaml'])

    def test_existing_service_selectors_are_preserved(self):
        name = 'Install-LocalRoadmaps.py'
        self.assertEqual(('space', 'learning'), guard.selected_services(name))
        self.assertEqual(('planning',), guard.selected_services(name, {'planning_only': True}))
        self.assertEqual(('brain-query',), guard.selected_services(name, {'brain_query_only': True}))
        self.assertEqual(('gateway',), guard.selected_services(name, {'gateway': True}))
        self.assertEqual(('gateway',), guard.selected_services('Install-LocalBrainNetwork.py', {'gateway': True, 'enable': True}))
        self.assertEqual(('brain-query',), guard.selected_services('Install-LocalBrainNetwork.py', {'enable': True}))

    def test_roadmap_argument_errors_and_help_precede_the_compatibility_check(self):
        name = 'Install-LocalRoadmaps.py'
        fake = self.root / 'scripts' / name
        source = (SCRIPTS / name).read_text(encoding='utf-8')
        for flags, status in ((['--help'], 0), (['--helm', 'unused', '--gateway', '--planning-only'], 2)):
            with self.subTest(flags=flags), \
                    patch.object(sys, 'argv', [str(fake), *flags]), \
                    patch.object(guard, 'require_tag_baseline', side_effect=AssertionError('guard before parser')) as check, \
                    patch.object(subprocess, 'run', side_effect=AssertionError('command before validation')) as run, \
                    redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as stopped:
                    exec(compile(source, str(fake), 'exec'), {'__name__': '__main__', '__file__': str(fake)})
                self.assertEqual(status, stopped.exception.code)
                check.assert_not_called()
                run.assert_not_called()

    def test_pinned_refusal_preserves_bytes_and_gives_current_supported_workflow(self):
        self.pinned()
        before = {path: path.read_bytes() for path in self.values.iterdir()}
        with self.assertRaises(SystemExit) as stopped:
            guard.require_tag_baseline('Install-LocalPublicDiscovery.py', self.root)
        message = str(stopped.exception)
        for expected in ('docs/java-runtime.md', 'Run-DeliveryCI.py --images',
                         'scan the exact archives', 'both nodes', 'image.digest',
                         'empty image.tag', 'docs/delivery.md', 'Do not clear a current digest'):
            self.assertIn(expected, message)
        self.assertEqual(before, {path: path.read_bytes() for path in self.values.iterdir()})

    def test_malformed_affected_image_values_fail_closed(self):
        path = self.values / 'gateway-values.yaml'
        cases = [b'', b'[]', b'image: null', b'image: []', b'image: string',
                 b'image: {tag: 42}', b'image: {tag: null}', b'image: {digest: false}',
                 b'image: {repository: []}', b'image: {repository: " "}',
                 b'image: {digest: sha256:short}',
                 ('image: {tag: old, digest: "sha256:' + 'a' * 64 + '"}').encode(),
                 b'image: {tag: old}\nimage: {tag: another}',
                 b'image: {digest: "", digest: ""}', b'image: [',
                 b'image: {tag: old}\n---\nimage: {tag: other}', b'\xff',
                 b'image: !!python/object/apply:os.system [echo forbidden]',
                 b'#' * (1024 * 1024 + 1)]
        for raw in cases:
            with self.subTest(raw=raw[:100]):
                path.write_bytes(raw)
                with self.assertRaisesRegex(SystemExit, 'invalid affected image values'):
                    guard.require_tag_baseline('Install-LocalGraphEditor.py', self.root)
                self.assertEqual(raw, path.read_bytes())
        path.unlink()
        with self.assertRaisesRegex(SystemExit, 'invalid affected image values'):
            guard.require_tag_baseline('Install-LocalGraphEditor.py', self.root)

    def test_tag_based_entrypoint_continues_to_original_read_only_context_check(self):
        class OriginalCommandReached(Exception):
            pass
        name = 'Install-LocalGraphEditor.py'
        fake = self.root / 'scripts' / name
        with patch.object(sys, 'argv', [str(fake), '--helm', 'unused-helm']), \
                patch.object(subprocess, 'run', side_effect=OriginalCommandReached) as run, \
                patch.object(Path, 'write_text', side_effect=AssertionError('unexpected write')):
            with self.assertRaises(OriginalCommandReached):
                exec(compile((SCRIPTS / name).read_text(encoding='utf-8'), str(fake), 'exec'),
                     {'__name__': '__main__', '__file__': str(fake)})
            self.assertEqual(run.call_count, 1)
            self.assertEqual(run.call_args.args[0][-2:], ['config', 'current-context'])

    def test_unknown_entrypoint_is_not_silently_treated_as_compatible(self):
        with self.assertRaisesRegex(ValueError, 'not inventoried'):
            guard.require_tag_baseline('Install-LocalUnknown.py', self.root)


if __name__ == '__main__':
    unittest.main()
