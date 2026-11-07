import hashlib,io,json,tarfile
from pathlib import Path
layer=io.BytesIO()
with tarfile.open(fileobj=layer,mode='w') as t:
 for source,target,mode in [('/artifacts/minio','minio',0o755),('/artifacts/bootstrap','bootstrap',0o755),('/source/LICENSE','licenses/LICENSE',0o644),('/source/source.tar.gz','licenses/source.tar.gz',0o644),('/scripts/bootstrap.go','licenses/bootstrap.go',0o644),('/scripts/build-source.json','licenses/build-source.json',0o644)]:
  data=Path(source).read_bytes();item=tarfile.TarInfo(target);item.size=len(data);item.mode=mode;t.addfile(item,io.BytesIO(data))
raw=layer.getvalue();diff='sha256:'+hashlib.sha256(raw).hexdigest();config=json.dumps(dict(architecture='amd64',os='linux',config=dict(User='10001:10001',Entrypoint=['/minio'],Cmd=['server','/data','--address',':9000','--console-address',':9001','--certs-dir','/tls'],Env=['HOME=/tmp']),rootfs=dict(type='layers',diff_ids=[diff])),separators=(',',':')).encode();filename=hashlib.sha256(config).hexdigest()+'.json';tag=json.loads(Path('/scripts/build-source.json').read_text())['image'];manifest=json.dumps([dict(Config=filename,RepoTags=[tag],Layers=['layer.tar'])]).encode()
with tarfile.open('/artifacts/image.tar','w') as t:
 for name,data in [(filename,config),('manifest.json',manifest),('layer.tar',raw)]:
  item=tarfile.TarInfo(name);item.size=len(data);t.addfile(item,io.BytesIO(data))
Path('/artifacts/image.sha256').write_text(hashlib.sha256(Path('/artifacts/image.tar').read_bytes()).hexdigest());print('MinIO source-built image packaged')
