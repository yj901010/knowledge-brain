"""Exercise exact-commit frontend CI in a throwaway local Git checkout."""
import argparse,hashlib,json,subprocess,shutil,sys,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    sys.stdout.reconfigure(encoding='utf-8')
    subprocess.run([sys.executable,str(ROOT/'scripts/Sync-PublicCurationWeb.py')],check=True)
    parser=argparse.ArgumentParser();parser.add_argument('--directory',default=str(ROOT/'.local/delivery'));options=parser.parse_args();directory=Path(options.directory).resolve();fixture=directory/'frontend-fixtures'/uuid.uuid4().hex;fixture.mkdir(parents=True)
    shutil.copytree(ROOT/'apps/graph-web',fixture/'apps/graph-web',ignore=shutil.ignore_patterns('node_modules','.next','out','tests','*.tsbuildinfo'))
    (fixture/'scripts/ci').mkdir(parents=True);(fixture/'infra/dependencies').mkdir(parents=True)
    for name in ['delivery.py','services.json']:shutil.copyfile(ROOT/'scripts/ci'/name,fixture/'scripts/ci'/name)
    shutil.copyfile(ROOT/'scripts/Build-GraphWeb.py',fixture/'scripts/Build-GraphWeb.py')
    shutil.copyfile(ROOT/'scripts/Sync-PublicCurationWeb.py',fixture/'scripts/Sync-PublicCurationWeb.py')
    (fixture/'data/public-curation').mkdir(parents=True)
    shutil.copyfile(ROOT/'data/public-curation/draft-pack-v1.json',fixture/'data/public-curation/draft-pack-v1.json')
    (fixture/'.gitignore').write_text('node_modules/\n.next/\nout/\n.local/\n')
    def git(*args):return subprocess.run(['git','-C',str(fixture),'-c','safe.directory='+str(fixture),'-c','user.name=Frontend CI drill','-c','user.email=frontend-ci@localhost.invalid','-c','core.autocrlf=false','-c','commit.gpgsign=false',*args],check=True,capture_output=True,text=True,encoding='utf-8').stdout.strip()
    git('init','-b','main');git('add','.');git('commit','-m','Local frontend CI fixture');revision=git('rev-parse','HEAD')
    lock=json.loads((ROOT/'infra/dependencies/delivery-tools.json').read_text())
    command=['docker','run','--rm','--memory=2g','--cpus=2','-e','NEXT_TELEMETRY_DISABLED=1','-v',str(fixture)+':/workspace','-w','/workspace',lock['images']['node'],'python3','scripts/ci/delivery.py','frontend']
    subprocess.run(command,check=True)
    sys.path.insert(0,str(ROOT/'scripts/ci'));from delivery import check_web_sources
    meta=check_web_sources(fixture/'.local/ci-web',revision,ROOT/'apps/graph-web')
    subprocess.run([sys.executable,str(ROOT/'scripts/Sync-PublicCurationWeb.py')],check=True)
    result=dict(fixture=str(fixture),fixtureSourceRevision=revision,nodeImage=lock['images']['node'],files=len(meta['files']),sourceRepositoryCommitCreated=False,artifactVerified=True,curationSourceSha256=hashlib.sha256((ROOT/'data/public-curation/draft-pack-v1.json').read_bytes()).hexdigest())
    (directory/'frontend-proof.json').write_text(json.dumps(result,indent=2));print('PASS frontend CI exact-fixture-commit build, source receipt, notices, CSP and complete asset hashes')
if __name__=='__main__':main()
