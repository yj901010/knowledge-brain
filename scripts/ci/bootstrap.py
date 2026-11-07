"""Fetch only reviewed, hash-pinned Linux CI tools; no install scripts or root writes."""
import argparse,hashlib,io,json,os,tarfile,urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
def install(name,output):
    lock=json.loads((ROOT/'infra/dependencies/delivery-tools.json').read_text())['tools'][name]
    with urllib.request.urlopen(lock['url'],timeout=90) as response:data=response.read()
    if hashlib.sha256(data).hexdigest()!=lock['sha256']:raise ValueError('Tool archive checksum mismatch: '+name)
    output=Path(output).resolve();output.mkdir(parents=True,exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as bundle:
        entries=[m for m in bundle.getmembers() if m.isfile() and Path(m.name).name==name]
        if len(entries)!=1:raise ValueError('Expected exactly one tool executable')
        target=output/name;target.write_bytes(bundle.extractfile(entries[0]).read());target.chmod(0o755)
    return target
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('tools',nargs='+',choices=['helm','actionlint','trivy']);p.add_argument('--output',default='.local/ci-tools');args=p.parse_args()
    for name in args.tools:print(install(name,args.output))
