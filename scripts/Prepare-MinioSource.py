import hashlib,io,json,tarfile,urllib.request
from pathlib import Path
meta=json.loads(Path('/scripts/build-source.json').read_text());data=urllib.request.urlopen(meta['sourceUrl'],timeout=60).read();assert hashlib.sha256(data).hexdigest()==meta['sourceSha256'];Path('/source/source.tar.gz').write_bytes(data)
with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as archive:
 for member in archive.getmembers():
  parts=Path(member.name).parts[1:]
  if not parts:continue
  assert not member.issym() and not member.islnk() and '..' not in parts
  dest=Path('/source').joinpath(*parts)
  if member.isdir():dest.mkdir(parents=True,exist_ok=True)
  elif member.isfile():dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(archive.extractfile(member).read())
print('Verified pinned MinIO source SHA256')
