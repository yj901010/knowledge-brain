import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('curation_web_sync', ROOT/'scripts/Sync-PublicCurationWeb.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class CurationWebSyncTests(unittest.TestCase):
    def fixture(self, root, source=b'{"draftState":"DRAFT"}\n', mirror=b'old mirror\n'):
        source_path=root/'data/public-curation/draft-pack-v1.json'
        mirror_path=root/'apps/graph-web/app/public-curation-pack.json'
        source_path.parent.mkdir(parents=True);mirror_path.parent.mkdir(parents=True)
        source_path.write_bytes(source);mirror_path.write_bytes(mirror)
        return source_path,mirror_path

    def test_stale_check_fails_without_modifying_either_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            source,mirror=self.fixture(Path(directory))
            before=(source.read_bytes(),mirror.read_bytes())
            with self.assertRaisesRegex(RuntimeError,'stale'):sync.sync(Path(directory))
            self.assertEqual(before,(source.read_bytes(),mirror.read_bytes()))

    def test_explicit_sync_preserves_source_and_exact_mirror_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            source,mirror=self.fixture(Path(directory),source='{"한글":"초안"}\r\n'.encode())
            expected=source.read_bytes();sync.sync(Path(directory),True);sync.sync(Path(directory))
            self.assertEqual(expected,source.read_bytes());self.assertEqual(expected,mirror.read_bytes())
            mirror.write_bytes(expected.replace(b'\r\n',b'\n'))
            with self.assertRaisesRegex(RuntimeError,'stale'):sync.sync(Path(directory))

    def test_missing_mirror_and_oversize_source_never_write_implicitly(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source,mirror=self.fixture(root)
            mirror.unlink()
            with self.assertRaisesRegex(RuntimeError,'stale'):sync.sync(root)
            self.assertFalse(mirror.exists())
            source.write_bytes(b'x'*131073)
            with self.assertRaisesRegex(RuntimeError,'128 KiB'):sync.sync(root,True)
            self.assertFalse(mirror.exists())

    def test_linked_paths_are_rejected_before_overwriting_the_mirror(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source,mirror=self.fixture(root)
            before=mirror.read_bytes()
            with patch.object(Path,'is_symlink',return_value=True):
                with self.assertRaisesRegex(RuntimeError,'within the project'):sync.sync(root,True)
            self.assertEqual(before,mirror.read_bytes())
