"""Real layered Docker archives verify the project license publication gate."""
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import call, patch

ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'scripts/ci'))
import delivery

SHA='a'*40
REPO='example/knowledge-brain'
LICENSE='licenses/knowledge-brain/project-LICENSE.txt'
NOTICE='licenses/knowledge-brain/project-NOTICE.txt'


def legal_sources(root):
    result={LICENSE:b'Original project license fixture\n',NOTICE:b'Original project notice fixture\n'}
    for name,data in result.items():
        source='LICENSE' if name==LICENSE else 'NOTICE'
        (root/source).write_bytes(data)
        mirror=root/'infra/runtime/legal'/('project-'+source+'.txt')
        mirror.parent.mkdir(parents=True,exist_ok=True);mirror.write_bytes(data)
    return result


def image_archive(path,layers):
    path.parent.mkdir(parents=True,exist_ok=True)
    with tarfile.open(path,'w') as outer:
        def add(name,data):
            member=tarfile.TarInfo(name);member.size=len(data);outer.addfile(member,io.BytesIO(data))
        names=[]
        for number,entries in enumerate(layers):
            payload=io.BytesIO()
            with tarfile.open(fileobj=payload,mode='w:gz') as layer:
                for name,value,kind in entries:
                    member=tarfile.TarInfo(name)
                    if kind=='directory':member.type=tarfile.DIRTYPE
                    elif kind in ['symlink','hardlink']:
                        member.type=tarfile.SYMTYPE if kind=='symlink' else tarfile.LNKTYPE;member.linkname='elsewhere'
                    else:member.size=len(value)
                    layer.addfile(member,io.BytesIO(value) if member.isfile() else None)
            name=str(number)+'.tar.gz';names.append(name);add(name,payload.getvalue())
        add('config.json',b'{}')
        add('manifest.json',json.dumps([dict(Config='config.json',RepoTags=['fixture:only'],Layers=names)]).encode())
    return path


def regular(files):return [(name,data,'file') for name,data in files.items()]


class ImageLegalTests(unittest.TestCase):
    def test_final_layer_bytes_and_same_layer_whiteout_order_are_used(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);files=legal_sources(root)
            archive=image_archive(root/'image.tar',[
                regular(files),[(LICENSE,b'older wrong license','file')],
                [(LICENSE,files[LICENSE],'file'),('licenses/knowledge-brain/.wh.project-LICENSE.txt',b'','file')]])
            with patch.object(delivery,'ROOT',root):
                self.assertEqual({'/'+name:hashlib.sha256(data).hexdigest() for name,data in files.items()},delivery.image_legal_hashes(archive))

    def test_missing_changed_overwritten_whiteout_and_nonregular_legal_files_are_rejected(self):
        cases={
            'missing':[[(LICENSE,b'Original project license fixture\n','file')]],
            'wrong-later':None,
            'target-symlink':None,
            'target-hardlink':None,
            'ancestor-symlink':None,
            'ancestor-file':None,
            'deleted-file':None,
            'deleted-ancestor':None,
            'opaque-directory':None,
            'directory-target':None,
            'child-of-file':None,
        }
        for label in cases:
            with self.subTest(case=label),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);files=legal_sources(root);layers=[regular(files)]
                if label=='missing':layers=cases[label]
                elif label=='wrong-later':layers.append([(LICENSE,b'wrong final bytes','file')])
                elif label=='target-symlink':layers.append([(LICENSE,b'','symlink')])
                elif label=='target-hardlink':layers.append([(LICENSE,b'','hardlink')])
                elif label=='ancestor-symlink':layers.extend([[('licenses',b'','symlink')],regular(files)])
                elif label=='ancestor-file':layers.append([('licenses/knowledge-brain',b'not a directory','file')])
                elif label=='deleted-file':layers.append([('licenses/knowledge-brain/.wh.project-NOTICE.txt',b'','file')])
                elif label=='deleted-ancestor':layers.append([('.wh.licenses',b'','file')])
                elif label=='opaque-directory':layers.append([('licenses/knowledge-brain/.wh..wh..opq',b'','file')])
                elif label=='directory-target':layers.append([(LICENSE,b'','directory')])
                elif label=='child-of-file':layers.append([(LICENSE+'/child',b'not a legal file','file')])
                archive=image_archive(root/'image.tar',layers)
                with patch.object(delivery,'ROOT',root),self.assertRaisesRegex(ValueError,'Final image project LICENSE/NOTICE'):
                    delivery.image_legal_hashes(archive)

    def test_source_and_runtime_mirror_drift_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);files=legal_sources(root);archive=image_archive(root/'image.tar',[regular(files)])
            (root/'infra/runtime/legal/project-NOTICE.txt').write_bytes(b'stale copy')
            with patch.object(delivery,'ROOT',root),self.assertRaisesRegex(ValueError,'source and image copy must match'):
                delivery.image_legal_hashes(archive)

    def test_bad_legal_archive_clears_stale_proof_and_stops_before_scan(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);legal_sources(root);target=root/'services/gateway-service/target'
            image_archive(target/'jib-image.tar',[[('unrelated',b'no legal files','file')]])
            for name in ['scan-proof.json','image.json']:(target/name).write_text('stale success')
            with patch.object(delivery,'ROOT',root),patch.object(delivery,'revision',return_value=SHA),patch.object(delivery,'maven'),patch.object(delivery,'run') as external:
                with self.assertRaisesRegex(ValueError,'Final image project LICENSE/NOTICE'):delivery.build_image('gateway-service',REPO,SHA)
                external.assert_not_called()
            self.assertFalse((target/'scan-proof.json').exists());self.assertFalse((target/'image.json').exists())

    def test_legal_hashes_bind_scan_proof_and_published_image_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);files=legal_sources(root);target=root/'services/gateway-service/target'
            archive=image_archive(target/'jib-image.tar',[regular(files)])
            def scan(*args,**kwargs):(target/'trivy.json').write_text('{"Results":[]}')
            with patch.object(delivery,'ROOT',root),patch.object(delivery,'revision',return_value=SHA),patch.object(delivery,'maven'),patch.object(delivery,'run',side_effect=scan):
                delivery.build_image('gateway-service',REPO,SHA)
            expected={'/'+name:hashlib.sha256(data).hexdigest() for name,data in files.items()}
            proof=json.loads((target/'scan-proof.json').read_text());self.assertEqual(expected,proof['legalFiles'])
            self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(),proof['imageTarSha256'])
            temporary_configs=[]
            def docker(command,**kwargs):
                self.assertNotIn('GHCR_TOKEN',kwargs['env'])
                self.assertNotEqual(['docker','login'],command[:2])
                config=Path(kwargs['env']['DOCKER_CONFIG'])/'config.json'
                self.assertEqual({'auths':{'ghcr.io':{'auth':base64.b64encode(b'fixture:disposable-test-only').decode('ascii')}}},json.loads(config.read_text()))
                temporary_configs.append(config)
                return SimpleNamespace(stdout=json.dumps(['ghcr.io/'+REPO+'/gateway-service@sha256:'+'c'*64]))
            package='ghcr.io/'+REPO+'/gateway-service'
            policy=root/'workflow-checkout';policy.mkdir()
            with patch.object(delivery,'ROOT',policy),patch.object(delivery,'revision',return_value=SHA) as source_check,patch.dict(os.environ,GHCR_USER='fixture',GHCR_TOKEN='disposable-test-only'):
                with patch.object(delivery,'require_private_package',side_effect=ValueError('package not private')),patch.object(delivery,'run') as external:
                    with self.assertRaisesRegex(ValueError,'package not private'):
                        delivery.publish('gateway-service',REPO,SHA,source_directory=root)
                    external.assert_not_called()
                    self.assertFalse((target/'image.json').exists())
                with patch.object(delivery,'require_private_package',side_effect=[None,ValueError('package changed')]),patch.object(delivery,'run',side_effect=docker):
                    with self.assertRaisesRegex(ValueError,'package changed'):
                        delivery.publish('gateway-service',REPO,SHA,source_directory=root)
                    self.assertFalse((target/'image.json').exists())
                with patch.object(delivery,'require_private_package') as privacy,patch.object(delivery,'run',side_effect=docker):
                    delivery.publish('gateway-service',REPO,SHA,source_directory=root)
                self.assertEqual([call(package,'disposable-test-only'),call(package,'disposable-test-only')],privacy.call_args_list)
                self.assertEqual(3,source_check.call_count)
                for attempt in source_check.call_args_list:
                    self.assertEqual((SHA,),attempt.args)
                    self.assertEqual(root.resolve(),attempt.kwargs['cwd'])
                    self.assertNotIn('GHCR_TOKEN',attempt.kwargs['env'])
            self.assertTrue(temporary_configs)
            self.assertFalse(any(config.exists() for config in temporary_configs))
            self.assertEqual(expected,json.loads((target/'image.json').read_text())['legalFiles'])

    def test_archive_changed_during_scan_never_gets_success_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);files=legal_sources(root);target=root/'services/gateway-service/target'
            archive=image_archive(target/'jib-image.tar',[regular(files)])
            def changed(*args,**kwargs):
                (target/'trivy.json').write_text('{"Results":[]}');archive.write_bytes(archive.read_bytes()+b'changed during scan')
            with patch.object(delivery,'ROOT',root),patch.object(delivery,'revision',return_value=SHA),patch.object(delivery,'maven'),patch.object(delivery,'run',side_effect=changed):
                with self.assertRaisesRegex(ValueError,'Image changed during legal/scan validation'):delivery.build_image('gateway-service',REPO,SHA)
            self.assertFalse((target/'scan-proof.json').exists())

    def test_missing_or_changed_legal_proof_cannot_log_in_or_publish(self):
        for bad in [None,{'/licenses/knowledge-brain/project-LICENSE.txt':'0'*64}]:
            with self.subTest(proof=bad),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);files=legal_sources(root);target=root/'services/gateway-service/target'
                archive=image_archive(target/'jib-image.tar',[regular(files)]);report=target/'trivy.json';report.write_text('{"Results":[]}')
                proof=dict(sourceRevision=SHA,service='gateway-service',repository='ghcr.io/'+REPO+'/gateway-service',scanPassed=True,imageTarSha256=hashlib.sha256(archive.read_bytes()).hexdigest(),reportSha256=hashlib.sha256(report.read_bytes()).hexdigest(),legalFiles=bad)
                delivery.write(target/'scan-proof.json',proof)
                with patch.object(delivery,'ROOT',root),patch.object(delivery,'revision',return_value=SHA),patch.object(delivery,'run') as external:
                    with self.assertRaisesRegex(ValueError,'Current project legal proof required'):delivery.publish('gateway-service',REPO,SHA)
                    external.assert_not_called()
                self.assertFalse((target/'image.json').exists())
