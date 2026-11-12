import copy,hashlib,importlib.util,json,os,subprocess,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'scripts/ci'))
import delivery,gitops
import yaml
from unittest.mock import patch
SHA='a'*40;OTHER='b'*40;REPO='example/knowledge-brain'
def release():return dict(schemaVersion=1,repository=REPO,sourceRevision=SHA,images={s['name']:dict(repository='ghcr.io/'+REPO+'/'+s['name'],digest='sha256:'+'c'*64) for s in delivery.SERVICES})
class ReleaseTests(unittest.TestCase):
    def test_requires_all_independent_images(self):
        value=release();value['images'].pop('learning-service')
        with self.assertRaises(ValueError):delivery.validate_lock(value)
    def test_rejects_mutable_or_foreign_image(self):
        for key,value in [('digest','latest'),('repository','ghcr.io/other/brain/gateway-service')]:
            lock=release();lock['images']['gateway-service'][key]=value
            with self.assertRaises(ValueError):delivery.validate_lock(lock)
    def test_assembly_rejects_mixed_revision_duplicate_and_unscanned(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            for name,image in release()['images'].items():delivery.write(root/name/'image.json',dict(image,service=name,sourceRevision=SHA,scanPassed=True))
            self.assertEqual(13,len(delivery.assemble(root,REPO,SHA)['images']))
            path=root/'gateway-service/image.json';original=json.loads(path.read_text())
            for change in [dict(sourceRevision=OTHER),dict(scanPassed=False)]:
                delivery.write(path,dict(original,**change))
                with self.assertRaises(ValueError):delivery.assemble(root,REPO,SHA)
            delivery.write(path,original);delivery.write(root/'duplicate/image.json',original)
            with self.assertRaises(ValueError):delivery.assemble(root,REPO,SHA)
    def test_asset_commit_and_contents_are_bound(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'graph').mkdir()
            for name in ['index.html','csp.txt']:(root/'graph'/name).write_text(name)
            delivery.write(root/'manifest.json',dict(sourceRevision=SHA,files=delivery.files_hashes(root/'graph')))
            delivery.check_web(root,SHA)
            with self.assertRaises(ValueError):delivery.check_web(root,OTHER)
            (root/'graph/index.html').write_text('tampered')
            with self.assertRaises(ValueError):delivery.check_web(root,SHA)
    def test_gitops_cas_immutable_and_no_automatic_sync(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);rid=gitops.prepare(release(),'https://github.com/'+REPO,OTHER,root)
            pointer=root/'environments/local/release.json';before=pointer.read_bytes()
            with self.assertRaises(ValueError):gitops.prepare(release(),'https://github.com/'+REPO,OTHER,root)
            self.assertEqual(before,pointer.read_bytes())
            data=json.loads((root/'applications.json').read_text());self.assertEqual(14,len(data['items']))
            for item in data['items'][1:]:
                self.assertNotIn('automated',item['spec']['syncPolicy']);self.assertNotIn('finalizers',item['metadata']);self.assertEqual(OTHER,item['spec']['source']['targetRevision'])
            target=root/'releases/local'/rid/'gateway-service.json';target.write_text('{}')
            with self.assertRaises(ValueError):gitops.prepare(release(),'https://github.com/'+REPO,OTHER,root,hashlib.sha256(before).hexdigest())
    def test_gitops_concurrent_writer_guard(self):
        with tempfile.TemporaryDirectory() as temp:
            (Path(temp)/'.promotion.lock').write_text('active')
            with self.assertRaises(FileExistsError):gitops.prepare(release(),'https://github.com/'+REPO,OTHER,temp)
    def test_no_credentials_or_unreviewed_production(self):
        for url in ['https://token@github.com/a/b','http://github.com/a/b','https://github.com/a/b?token=x']:
            with self.assertRaises(ValueError):gitops.repo_url(url)
        with self.assertRaises(ValueError):gitops.application('gateway-service','https://github.com/'+REPO,OTHER,'x','production')
    def test_gitops_configuration_commit_must_contain_values(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);out=root/'infra/gitops';rid=gitops.stage_release(release(),out)
            def git(*args):return subprocess.run(['git','-C',str(root),'-c','safe.directory='+str(root),'-c','user.name=Delivery test','-c','user.email=test@localhost.invalid','-c','commit.gpgsign=false','-c','core.autocrlf=false',*args],check=True,capture_output=True,text=True,encoding='utf-8').stdout.strip()
            git('init');git('add','.');git('commit','-m','Stage exact digest values');sha=git('rev-parse','HEAD')
            gitops.verify_config_tree(out,rid,sha,root)
            (out/'releases/local'/rid/'gateway-service.json').write_text('{}')
            with self.assertRaises(ValueError):gitops.verify_config_tree(out,rid,sha,root)
    def test_blocking_scan_never_creates_publish_proof(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);target=root/'services/gateway-service/target';target.mkdir(parents=True);(target/'jib-image.tar').write_bytes(b'fake image');(target/'scan-proof.json').write_text('stale previous successful scan');(target/'image.json').write_text('stale previous published fragment')
            with patch.object(delivery,'ROOT',root),patch.object(delivery,'revision',return_value=SHA),patch.object(delivery,'maven'),patch.object(delivery,'image_legal_hashes',return_value={'/licenses/knowledge-brain/project-LICENSE.txt':'fixture','/licenses/knowledge-brain/project-NOTICE.txt':'fixture'}) as legal,patch.object(delivery,'run',side_effect=subprocess.CalledProcessError(1,'trivy')) as scanner:
                with self.assertRaises(subprocess.CalledProcessError):delivery.build_image('gateway-service',REPO,SHA)
            legal.assert_called_once_with(target/'jib-image.tar');self.assertEqual(1,scanner.call_count)
            self.assertFalse((target/'scan-proof.json').exists())
            self.assertFalse((target/'image.json').exists())
    def test_changed_scanned_image_cannot_reach_registry(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);target=root/'services/gateway-service/target';target.mkdir(parents=True);(target/'jib-image.tar').write_bytes(b'changed')
            delivery.write(target/'scan-proof.json',dict(sourceRevision=SHA,service='gateway-service',repository='ghcr.io/'+REPO+'/gateway-service',scanPassed=True,imageTarSha256='0'*64))
            with patch.object(delivery,'ROOT',root),patch.object(delivery,'revision',return_value=SHA),patch.object(delivery,'run') as external:
                with self.assertRaises(ValueError):delivery.publish('gateway-service',REPO,SHA)
                external.assert_not_called()
    def test_dependency_licenses_match_reviewed_files(self):
        lock=json.loads((ROOT/'infra/dependencies/delivery-tools.json').read_text())
        for item in list(lock['actions'].values())+list(lock['tools'].values())+[lock['argocd'],lock['redisLicense']]:
            self.assertEqual(item['licenseSha256'],hashlib.sha256((ROOT/item['licenseFile']).read_bytes()).hexdigest())
    def test_workflows_have_no_unpinned_actions_or_pr_write_tokens(self):
        lock=json.loads((ROOT/'infra/dependencies/delivery-tools.json').read_text())
        for path in (ROOT/'.github/workflows').glob('*.yml'):
            doc=yaml.load(path.read_text(),Loader=yaml.BaseLoader)
            self.assertEqual({'contents':'read'},doc['permissions'])
            for job in doc['jobs'].values():
                for step in job.get('steps',[]):
                    action=step.get('uses','')
                    if action:
                        name,sha=action.split('@');self.assertEqual(lock['actions'][name]['sha'],sha)
        ci=yaml.load((ROOT/'.github/workflows/ci.yml').read_text(),Loader=yaml.BaseLoader)
        self.assertTrue(all('permissions' not in job for job in ci['jobs'].values()))
    def test_release_requires_private_packages_even_for_an_older_image_source(self):
        workflow=yaml.load((ROOT/'.github/workflows/release.yml').read_text(),Loader=yaml.BaseLoader)
        source_steps=workflow['jobs']['source']['steps']
        source_runs=[step['run'] for step in source_steps if 'run' in step]
        self.assertIn('test "$(git rev-parse HEAD)" = "$SOURCE_REVISION"',source_runs[0])
        self.assertIn('git merge-base --is-ancestor "$SOURCE_REVISION" "refs/remotes/origin/$DEFAULT_BRANCH"',source_runs[0])
        self.assertNotIn('delivery.py',source_runs[0])
        registry=workflow['jobs']['registry']
        images=workflow['jobs']['images']
        self.assertEqual({'source','validate'},set(registry['needs']))
        self.assertIn('registry',images['needs'])
        for job in (registry,images):
            self.assertEqual('ghcr-release',job['environment'])
            self.assertNotIn('permissions',job)
            self.assertTrue(any(step.get('with',{}).get('ref')=='${{ github.workflow_sha }}' for step in job['steps']))
        preflight=registry['steps'][-1]
        self.assertIn('ghcr_private.py',preflight['run'])
        publish=next(step for step in images['steps'] if 'delivery.py publish' in step.get('run',''))
        policy_checkout=next(step for step in images['steps'] if step.get('with',{}).get('ref')=='${{ github.workflow_sha }}')
        self.assertEqual('.local/release-policy',policy_checkout['with']['path'])
        self.assertLess(images['steps'].index(policy_checkout),images['steps'].index(publish))
        self.assertTrue(publish['run'].strip().startswith('python .local/release-policy/scripts/ci/delivery.py publish --source-directory . '))
        for step in (preflight,publish):
            self.assertEqual('${{ secrets.GHCR_PUBLISH_TOKEN }}',step['env']['GHCR_TOKEN'])
class ChartTests(unittest.TestCase):
    def helm(self,*args,ok=True):
        result=subprocess.run([os.environ.get('HELM','helm'),'template','gateway-service',str(ROOT/'infra/charts/brain-service'),*args],capture_output=True,text=True)
        if ok:self.assertEqual(0,result.returncode,result.stderr);return list(yaml.safe_load_all(result.stdout))
        self.assertNotEqual(0,result.returncode)
    def test_existing_tag_and_telemetry(self):
        docs=self.helm('--set','image.repository=example/gateway','--set','image.tag=local','--set','observability.enabled=true')
        pod=next(d for d in docs if d['kind']=='Deployment')['spec']['template']['spec'];self.assertEqual('example/gateway:local',pod['containers'][0]['image']);self.assertEqual(1,len(pod['initContainers']))
    def test_digest_and_pull_secret(self):
        docs=self.helm('--set','image.repository=ghcr.io/example/gateway','--set','image.digest=sha256:'+'a'*64,'--set','imagePullSecrets[0].name=registry')
        pod=next(d for d in docs if d['kind']=='Deployment')['spec']['template']['spec'];self.assertEqual('ghcr.io/example/gateway@sha256:'+'a'*64,pod['containers'][0]['image']);self.assertEqual([dict(name='registry')],pod['imagePullSecrets'])
    def test_ambiguous_invalid_and_missing_image_fail(self):
        for args in [[],['--set','image.repository=x','--set','image.digest=sha256:no'],['--set','image.repository=x','--set','image.tag=local','--set','image.digest=sha256:'+'a'*64]]:self.helm(*args,ok=False)
    def test_all_thirteen_release_overlays_keep_service_identity_and_telemetry(self):
        with tempfile.TemporaryDirectory() as temp:
            rid=gitops.stage_release(release(),temp)
            for item in delivery.SERVICES:
                name=item['name'];stem=name.removesuffix('-service');files=[ROOT/'infra/services'/f'{stem}-values.yaml',ROOT/'infra/environments/local'/f'{stem}-values.yaml',ROOT/'infra/environments/local/observed-service-values.yaml',Path(temp)/'releases/local'/rid/f'{name}.json']
                command=[os.environ.get('HELM','helm'),'template',name,str(ROOT/'infra/charts/brain-service'),'--namespace','brain-apps']
                for file in files:command+=['-f',str(file)]
                result=subprocess.run(command,capture_output=True,text=True);self.assertEqual(0,result.returncode,name+result.stderr)
                docs=list(yaml.safe_load_all(result.stdout));deployment=next(d for d in docs if d['kind']=='Deployment');pod=deployment['spec']['template']
                self.assertEqual(name,deployment['metadata']['name']);self.assertEqual('true',pod['metadata']['labels']['brain-telemetry']);self.assertIn('argocd.argoproj.io/sync-options',deployment['metadata']['annotations']);self.assertEqual('ghcr.io/'+REPO+'/'+name+'@sha256:'+'c'*64,pod['spec']['containers'][0]['image'])
    def test_argocd_never_writes_domain_namespace(self):
        spec=importlib.util.spec_from_file_location('local_delivery',ROOT/'scripts/Render-LocalDelivery.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        docs=module.render();self.assertFalse(any(d['kind'].startswith('Cluster') for d in docs))
        self.assertTrue(all(d['metadata'].get('namespace') in [None,'brain-delivery','brain-delivery-probe'] for d in docs))
        secret=next(d for d in docs if d['metadata']['name']=='local-probe-cluster');self.assertEqual('brain-delivery-probe',secret['stringData']['namespaces']);self.assertEqual('{}',secret['stringData']['config'])
