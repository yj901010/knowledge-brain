"""Real Git fixtures for source publication boundaries; never inspect the real repo."""
import base64
from contextlib import redirect_stderr
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/Prepare-SourceReview.py'
spec = importlib.util.spec_from_file_location('source_review', SCRIPT)
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)


class SourceReviewTests(unittest.TestCase):
    def git(self, root, *args):
        return subprocess.run(['git', '-c', 'safe.directory=' + str(root), '-C', str(root), *args],
                              check=True, capture_output=True)

    def fixture(self, directory):
        root = Path(directory) / 'repo'; root.mkdir()
        self.git(root, 'init', '-q')
        (root / '.gitignore').write_text('.local/\nignored.txt\n.env*\n!.env.example\n', encoding='utf-8')
        (root / 'app.py').write_text('print("initial source")\n', encoding='utf-8')
        return root

    def cli(self, root, destination='.local/review'):
        return subprocess.run([sys.executable, str(SCRIPT), '--root', str(root), '--directory', destination],
                              capture_output=True, text=True, encoding='utf-8')

    def private_fixture(self):
        # Deterministic disposable Ed25519 PKCS8 material, assembled only at runtime.
        der = bytes.fromhex('302e020100300506032b657004220420') + bytes(range(32))
        kind = b'PRIVATE ' + b'KEY'
        return b'-----BEGIN ' + kind + b'-----\n' + base64.b64encode(der) + b'\n-----END ' + kind + b'-----\n'

    def test_working_source_archive_has_exact_bytes_and_respects_git_ignore(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.fixture(directory)
            self.git(root, 'add', '.gitignore', 'app.py')
            (root / 'app.py').write_text('print("unstaged current source — 한글")\n', encoding='utf-8')
            (root / 'ignored.txt').write_bytes(self.private_fixture())
            (root / '.env.example').write_text('DATABASE_PASSWORD=replace-me\n', encoding='utf-8')
            (root / 'ci.yml').write_text('POSTGRES_PASSWORD: ci-ephemeral-only\n', encoding='utf-8')
            (root / 'generate.py').write_text("header = '-----BEGIN PRIVATE KEY-----\\n'\n", encoding='utf-8')
            result = self.cli(root)
            self.assertEqual(0, result.returncode, result.stderr)
            folder = root / '.local/review'; manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
            self.assertEqual('READY_FOR_LOCAL_REVIEW', manifest['state'])
            self.assertFalse(manifest['sourceRepositoryCommitCreated']); self.assertFalse(manifest['remoteCallsPerformed'])
            self.assertEqual({'.gitignore', 'app.py', '.env.example', 'ci.yml', 'generate.py'}, {p['path'] for p in manifest['files']})
            self.assertEqual(review.sha256(review._canonical(manifest['files'])), manifest['sourceTreeSha256'])
            archive = folder / manifest['archive']['path']
            self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), manifest['archive']['sha256'])
            with tarfile.open(archive, 'r:gz') as tar:
                self.assertEqual([p['path'] for p in manifest['files']], tar.getnames())
                for record in manifest['files']:
                    payload = tar.extractfile(record['path']).read()
                    self.assertEqual((root / record['path']).read_bytes(), payload)
                    self.assertEqual(record['bytes'], len(payload))
                    self.assertEqual(record['sha256'], hashlib.sha256(payload).hexdigest())
            self.assertNotEqual(0, self.git_status_head(root))

    def git_status_head(self, root):
        return subprocess.run(['git', '-C', str(root), '-c', 'safe.directory=' + str(root),
                               'rev-parse', '--verify', 'HEAD'], capture_output=True).returncode

    def test_real_cmd_file_is_archived_without_masking_readonly_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.fixture(directory); wrapper = root / 'mvnw.cmd'
            payload = b'@echo off\r\nrem disposable source review fixture\r\nexit /b 0\r\n'
            wrapper.write_bytes(payload)
            before = wrapper.lstat()
            with wrapper.open('rb') as stream:
                opened = os.fstat(stream.fileno())
            self.assertEqual(review._stamp(before), review._stamp(opened))
            result = self.cli(root)
            self.assertEqual(0, result.returncode, result.stderr)
            folder = root / '.local/review'
            manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
            record = next(p for p in manifest['files'] if p['path'] == 'mvnw.cmd')
            self.assertEqual(hashlib.sha256(payload).hexdigest(), record['sha256'])
            with tarfile.open(folder / 'source.tar.gz', 'r:gz') as archive:
                self.assertEqual(payload, archive.extractfile('mvnw.cmd').read())
                self.assertEqual(record['mode'], archive.getmember('mvnw.cmd').mode)
            original = review.write_archive
            def readonly_change(*args):
                wrapper.chmod(stat.S_IREAD)
                return original(*args)
            with patch.object(review, 'write_archive', side_effect=readonly_change):
                with self.assertRaisesRegex(review.SourceReviewError, 'Source changed'):
                    review.prepare_review(root, '.local/readonly-change')
            self.assertEqual(payload, wrapper.read_bytes())
            self.assertFalse((root / '.local/readonly-change/manifest.json').exists())

    def test_tracked_ignored_operational_paths_are_rejected_before_artifacts(self):
        paths = ['.env.live', '.local/volume/data.bin', 'work/result.txt', 'src/target/output.txt',
                 'node_modules/pkg/index.js', 'client.p12', 'config/kubeconfig.yaml', 'snapshot.tar.gz']
        for relative in paths:
            with self.subTest(path=relative), tempfile.TemporaryDirectory() as directory:
                root = self.fixture(directory); path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(b'private operational fixture')
                self.git(root, 'add', '--force', relative)
                result = self.cli(root)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Operational or generated source path', result.stderr)
                self.assertEqual(b'private operational fixture', path.read_bytes())
                self.assertFalse((root / '.local/review').exists())

    def test_actual_private_pem_is_rejected_in_an_ordinary_source_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.fixture(directory); key = self.private_fixture()
            (root / 'notes.txt').write_bytes(key)
            self.git(root, 'add', 'notes.txt')
            result = self.cli(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('Actual private-key material', result.stderr)
            self.assertNotIn(base64.b64encode(bytes(range(32))).decode(), result.stderr)
            self.assertFalse((root / '.local/review').exists())

    def test_renamed_binary_keystore_and_credential_kubeconfig_are_rejected(self):
        contents = [b'\xfe\xed\xfe\xed' + bytes(64),
                    bytes.fromhex('3018020103301306092a864886f70d010701a006040430020000'),
                    b'apiVersion: v1\nkind: Config\nclusters:\ncontexts:\nusers:\n  user:\n    token: opaque-runtime-credential\n']
        for value in contents:
            with self.subTest(kind=value[:4]), tempfile.TemporaryDirectory() as directory:
                root = self.fixture(directory); (root / 'ordinary.txt').write_bytes(value)
                result = self.cli(root)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((root / '.local/review').exists())

    def test_existing_review_is_never_overwritten_and_outside_outputs_are_denied(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.fixture(directory); self.assertEqual(0, self.cli(root).returncode)
            folder = root / '.local/review'
            original = {p.name:p.read_bytes() for p in folder.iterdir()}
            result = self.cli(root)
            self.assertNotEqual(0, result.returncode); self.assertIn('never be overwritten', result.stderr)
            self.assertEqual(original, {p.name:p.read_bytes() for p in folder.iterdir()})
            result = self.cli(root, '../outside-review')
            self.assertNotEqual(0, result.returncode); self.assertFalse((Path(directory) / 'outside-review').exists())

    def test_source_content_change_during_archive_never_issues_success_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.fixture(directory); original = review.write_archive
            def changed(*args):
                (root / 'app.py').write_text('changed after the reviewed hash\n', encoding='utf-8')
                return original(*args)
            with patch.object(review, 'write_archive', side_effect=changed):
                with self.assertRaisesRegex(review.SourceReviewError, 'Source changed'):
                    review.prepare_review(root, '.local/review')
            self.assertFalse((root / '.local/review/manifest.json').exists())

    def test_chmod_failure_exits_cli_without_publishing_ready_manifest(self):
        for failing_name in ['source.tar.gz', 'manifest.pending.json']:
            with self.subTest(chmod=failing_name), tempfile.TemporaryDirectory() as directory:
                root = self.fixture(directory); original = Path.chmod
                def denied(path, *args, **kwargs):
                    if path.name == failing_name:
                        raise PermissionError('Fixture denied chmod')
                    return original(path, *args, **kwargs)
                stderr = io.StringIO()
                with patch.object(Path, 'chmod', denied), patch.object(sys, 'argv',
                        [str(SCRIPT), '--root', str(root), '--directory', '.local/review']), redirect_stderr(stderr):
                    with self.assertRaises(SystemExit) as stopped:
                        review.main()
                self.assertEqual(1, stopped.exception.code)
                self.assertIn('No successful manifest was issued', stderr.getvalue())
                folder = root / '.local/review'
                self.assertTrue((folder / 'source.tar.gz').is_file())
                self.assertTrue((folder / 'manifest.pending.json').is_file())
                self.assertFalse((folder / 'manifest.json').exists())
                self.assertEqual('print("initial source")\n', (root / 'app.py').read_text(encoding='utf-8'))

    def test_new_source_after_archive_is_detected_even_when_payloads_are_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.fixture(directory); original = review.write_archive
            def added(*args):
                archive = original(*args); (root / 'added.py').write_text('new source\n', encoding='utf-8'); return archive
            with patch.object(review, 'write_archive', side_effect=added):
                with self.assertRaisesRegex(review.SourceReviewError, 'Source changed'):
                    review.prepare_review(root, '.local/review')
            self.assertFalse((root / '.local/review/manifest.json').exists())

    def test_reparse_ancestors_and_untrusted_git_paths_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.fixture(directory); original = Path.lstat
            def reparse(path, *args, **kwargs):
                info = original(path, *args, **kwargs)
                if path == root / 'app.py':
                    return SimpleNamespace(st_mode=info.st_mode, st_file_attributes=0x400)
                return info
            with patch.object(Path, 'lstat', reparse):
                with self.assertRaisesRegex(review.SourceReviewError, 'reparse'):
                    review.prepare_review(root, '.local/review')
            with patch.object(review, '_git', return_value=SimpleNamespace(returncode=0, stdout=b'../escaped.txt\0')):
                with self.assertRaisesRegex(review.SourceReviewError, 'Unsafe source path'):
                    review.selected_paths(root)
            self.assertFalse((root / '.local/review').exists())

    def test_real_symlink_cannot_read_an_external_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.fixture(directory); outside = Path(directory) / 'outside.txt'; outside.write_text('external fixture')
            try:
                (root / 'linked.txt').symlink_to(outside)
            except OSError:
                self.skipTest('The OS account cannot create file symlinks; reparse metadata boundary is tested separately')
            result = self.cli(root)
            self.assertNotEqual(0, result.returncode); self.assertIn('Linked or reparse', result.stderr)
            self.assertEqual('external fixture', outside.read_text())
            self.assertFalse((root / '.local/review').exists())

    @unittest.skipUnless(os.name == 'nt', 'Windows junction boundary; file symlink test covers POSIX')
    def test_real_windows_junction_cannot_read_external_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.fixture(directory); outside = Path(directory) / 'outside'; outside.mkdir()
            (outside / 'fixture.txt').write_text('external directory fixture')
            link = root / 'linked'; env = os.environ.copy()
            env.update(BRAIN_TEST_JUNCTION_LINK=str(link), BRAIN_TEST_JUNCTION_TARGET=str(outside))
            created = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command',
                'New-Item -ItemType Junction -Path $env:BRAIN_TEST_JUNCTION_LINK -Target $env:BRAIN_TEST_JUNCTION_TARGET -ErrorAction Stop | Out-Null'],
                env=env, capture_output=True, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            if created.returncode:
                self.skipTest('The OS account cannot create junctions; reparse metadata boundary is tested separately')
            try:
                self.assertTrue(link.resolve().is_relative_to(Path(directory).resolve()))
                self.git(root, 'add', '--force', 'linked/fixture.txt')
                result = self.cli(root)
                self.assertNotEqual(0, result.returncode); self.assertIn('Linked or reparse', result.stderr)
                self.assertFalse((root / '.local/review').exists())
                self.assertEqual('external directory fixture', (outside / 'fixture.txt').read_text())
            finally:
                # Remove only this checked test junction, never its target recursively.
                self.assertEqual(outside.resolve(), link.resolve())
                os.rmdir(link)

    def test_oversize_source_and_nonignored_output_fail_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.fixture(directory)
            with (root / 'large.bin').open('wb') as stream:stream.truncate(review.MAX_FILE_BYTES + 1)
            result = self.cli(root)
            self.assertNotEqual(0, result.returncode); self.assertIn('8 MiB bound', result.stderr)
            self.assertFalse((root / '.local/review').exists())
        with tempfile.TemporaryDirectory() as directory:
            root = self.fixture(directory); (root / '.gitignore').write_text('ignored.txt\n')
            result = self.cli(root)
            self.assertNotEqual(0, result.returncode); self.assertIn('Git-ignored', result.stderr)
            self.assertFalse((root / '.local/review').exists())
