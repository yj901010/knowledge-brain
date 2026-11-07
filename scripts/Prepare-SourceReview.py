"""Prepare an immutable, local working-tree source review; never commit or publish."""
import argparse
import base64
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import tarfile
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_TOTAL_BYTES = 128 * 1024 * 1024
MAX_FILES = 20000
DENIED_PARTS = {'.git', '.local', 'work', 'target', 'node_modules', '.next', 'out',
                '__pycache__', '.aws', '.azure', '.kube', '.ssh', '.gnupg', '.codex'}
DENIED_SUFFIXES = {'.p12', '.pfx', '.jks', '.keystore', '.key', '.vhd', '.vhdx',
                   '.qcow', '.qcow2', '.img', '.iso', '.dump', '.backup', '.bak',
                   '.tar', '.tgz', '.gz', '.zip', '.7z', '.rar', '.jar', '.war', '.class'}
DENIED_NAMES = {'id_rsa', 'id_dsa', 'id_ecdsa', 'id_ed25519', 'credentials.json',
                'auth.json', 'password', 'password.txt', 'token', 'token.txt'}
PRIVATE_PEM = re.compile(
    rb'(?m)^-----BEGIN ((?:RSA |EC |DSA |ENCRYPTED |OPENSSH )?PRIVATE KEY)-----\r?\n'
    rb'(.+?)^-----END \1-----[ \t]*\r?$', re.DOTALL)


class SourceReviewError(RuntimeError):
    pass


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def _canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')


def _git(root, *args, input_data=None):
    env = os.environ.copy()
    for name in ['GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR']:
        env.pop(name, None)
    env['GIT_OPTIONAL_LOCKS'] = '0'
    result = subprocess.run(['git', '-c', 'safe.directory=' + root.as_posix(),
                             '-c', 'core.fsmonitor=false', '-c', 'core.untrackedCache=false',
                             '-C', str(root), *args], input=input_data,
                            capture_output=True, env=env, timeout=30)
    return result


def _no_links(path):
    """Check existing ancestors, including Windows junction/reparse directories."""
    for part in [*reversed(path.parents), path]:
        try:
            info = part.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise SourceReviewError('Linked or reparse paths are forbidden: ' + str(path))


def _root(path):
    root = Path(os.path.abspath(path))
    _no_links(root)
    if not root.is_dir():
        raise SourceReviewError('Source root must be an existing directory')
    root = root.resolve(strict=True)
    result = _git(root, 'rev-parse', '--show-toplevel')
    if result.returncode or Path(result.stdout.decode('utf-8').strip()).resolve() != root:
        raise SourceReviewError('The exact Git working-tree root is required')
    return root


def _relative(value):
    path = PurePosixPath(value)
    if (not value or path.is_absolute() or '\\' in value or ':' in value
            or any(ord(c) < 32 or ord(c) == 127 for c in value)
            or any(part in {'', '.', '..'} for part in value.split('/'))):
        raise SourceReviewError('Unsafe source path')
    parts = [part.casefold() for part in path.parts]
    name = parts[-1]
    if (any(part in DENIED_PARTS for part in parts)
            or name in DENIED_NAMES or 'kubeconfig' in name
            or (name == '.env' or name.startswith('.env.')) and name != '.env.example'
            or path.suffix.casefold() in DENIED_SUFFIXES
            or value.casefold().startswith('services/gateway-service/src/main/resources/static/graph/')):
        raise SourceReviewError('Operational or generated source path is forbidden: ' + value)
    return path


def selected_paths(root):
    result = _git(root, 'ls-files', '--cached', '--others', '--exclude-standard', '-z')
    if result.returncode:
        raise SourceReviewError('Git source inventory failed')
    try:
        values = [raw.decode('utf-8') for raw in result.stdout.split(b'\0') if raw]
    except UnicodeDecodeError as error:
        raise SourceReviewError('Source paths must be UTF-8') from error
    if not values or len(values) > MAX_FILES or len(values) != len(set(values)):
        raise SourceReviewError('Invalid or excessive source inventory')
    if len({value.casefold() for value in values}) != len(values):
        raise SourceReviewError('Case-colliding source paths are forbidden')
    for value in values:
        _relative(value)
    return sorted(values)


def _content_allowed(data, relative):
    for match in PRIVATE_PEM.finditer(data):
        lines = match[2].replace(b'\r', b'').splitlines()
        encrypted = any(line.startswith(b'Proc-Type:') for line in lines)
        body = b''.join(line.strip() for line in lines
                        if line.strip() and not line.startswith((b'Proc-Type:', b'DEK-Info:')))
        try:
            decoded = base64.b64decode(body, validate=True)
        except ValueError:
            continue
        if len(decoded) >= 16 and (decoded.startswith((b'\x30', b'openssh-key-v1\0')) or encrypted):
            raise SourceReviewError('Actual private-key material is forbidden: ' + relative)
    if data.startswith(b'\xfe\xed\xfe\xed') or _pkcs12(data):
        raise SourceReviewError('Binary keystore material is forbidden: ' + relative)
    if (re.search(rb'(?m)^kind:[ \t]*Config[ \t]*\r?$', data)
            and all(re.search(rb'(?m)^' + field + rb':[ \t]*\r?$', data)
                    for field in [b'clusters', b'contexts', b'users'])
            and re.search(rb'(?m)^[ \t]+(?:token|client-key-data):[ \t]*\S+', data)):
        raise SourceReviewError('Credential-bearing kubeconfig is forbidden: ' + relative)


def _pkcs12(data):
    """Recognize the PFX version and ContentInfo OID, including renamed stores."""
    def tlv(offset):
        if offset + 2 > len(data):raise ValueError()
        tag = data[offset]; length = data[offset + 1]; offset += 2
        if length & 0x80:
            count = length & 0x7f
            if count < 1 or count > 4 or offset + count > len(data):raise ValueError()
            length = int.from_bytes(data[offset:offset + count], 'big'); offset += count
        if offset + length > len(data):raise ValueError()
        return tag, offset, offset + length
    try:
        outer, start, end = tlv(0)
        version, value, next_field = tlv(start)
        content, content_start, content_end = tlv(next_field)
        oid, oid_start, oid_end = tlv(content_start)
        return (outer == content == 0x30 and end == len(data) and version == 2
                and data[value:next_field] == b'\x03' and content_end <= end and oid == 6
                and data[oid_start:oid_end] in [bytes.fromhex('2a864886f70d010701'), bytes.fromhex('2a864886f70d010702')])
    except (ValueError, IndexError):
        return False


def _stamp(info):
    # Windows stat/fstat can expose different deprecated ctime values after an
    # overwrite. Creation time is stable there; byte hashes and mtime still bind
    # every read. Unix ctime remains a useful mutation guard.
    change = getattr(info, 'st_birthtime_ns', None) if os.name == 'nt' else info.st_ctime_ns
    # NTFS path stat infers execute bits for .cmd/.exe while fstat may not.
    # Only those inferred bits differ; preserve file type and read/write bits.
    mode = info.st_mode & ~0o111 if os.name == 'nt' else info.st_mode
    return (info.st_dev, info.st_ino, mode, info.st_size,
            info.st_mtime_ns, change, info.st_nlink)


def read_source(root, relative):
    path = root.joinpath(*_relative(relative).parts)
    _no_links(path)
    if not path.resolve(strict=True).is_relative_to(root):
        raise SourceReviewError('Source path escaped its root')
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
        raise SourceReviewError('Only regular, unlinked source files are allowed: ' + relative)
    if before.st_size > MAX_FILE_BYTES:
        raise SourceReviewError('Source file exceeds the 8 MiB bound: ' + relative)
    flags = os.O_RDONLY | getattr(os, 'O_BINARY', 0) | getattr(os, 'O_NOFOLLOW', 0)
    with os.fdopen(os.open(path, flags), 'rb') as stream:
        opened = os.fstat(stream.fileno())
        if _stamp(opened) != _stamp(before):
            raise SourceReviewError('Source changed while opening: ' + relative)
        data = stream.read(MAX_FILE_BYTES + 1)
        after = os.fstat(stream.fileno())
    _no_links(path)
    if (not path.resolve(strict=True).is_relative_to(root) or len(data) > MAX_FILE_BYTES
            or _stamp(before) != _stamp(after) or _stamp(before) != _stamp(path.lstat())):
        raise SourceReviewError('Source changed while reading: ' + relative)
    _content_allowed(data, relative)
    record = dict(path=relative, bytes=len(data), sha256=sha256(data),
                  mode=0o755 if before.st_mode & stat.S_IXUSR else 0o644)
    return record, _stamp(before), data


def snapshot(root):
    files, stamps, total = [], {}, 0
    for relative in selected_paths(root):
        record, stamp, _ = read_source(root, relative)
        files.append(record); stamps[relative] = stamp; total += record['bytes']
        if total > MAX_TOTAL_BYTES:
            raise SourceReviewError('Source inventory exceeds the 128 MiB bound')
    return files, stamps


def assert_unchanged(root, expected, stamps):
    actual, current_stamps = snapshot(root)
    if actual != expected or current_stamps != stamps:
        raise SourceReviewError('Source changed after the reviewed inventory')


def _destination(root, directory):
    path = Path(directory)
    path = Path(os.path.abspath(path if path.is_absolute() else root / path))
    _no_links(path)
    if not path.is_relative_to(root / '.local') or path == root / '.local':
        raise SourceReviewError('Review output must remain under the project .local directory')
    if path.exists():
        raise SourceReviewError('Existing review artifacts must never be overwritten')
    relative = path.relative_to(root).as_posix() + '/'
    result = _git(root, 'check-ignore', '--quiet', '--no-index', '--stdin', input_data=relative.encode('utf-8') + b'\n')
    if result.returncode != 0:
        raise SourceReviewError('Review output must be Git-ignored before writing')
    return path


def write_archive(root, directory, files, stamps):
    archive = directory / 'source.tar.gz'
    with archive.open('xb') as raw:
        with gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed:
            with tarfile.open(mode='w', fileobj=compressed, format=tarfile.PAX_FORMAT) as tar:
                for expected in files:
                    record, stamp, data = read_source(root, expected['path'])
                    if record != expected or stamp != stamps[expected['path']]:
                        raise SourceReviewError('Source changed during archive creation: ' + expected['path'])
                    info = tarfile.TarInfo(expected['path']); info.size = len(data)
                    info.mode = expected['mode']; info.mtime = 0; info.uid = info.gid = 0
                    tar.addfile(info, io.BytesIO(data))
        raw.flush(); os.fsync(raw.fileno())
    return archive


def prepare_review(root, directory):
    root = _root(root); destination = _destination(root, directory)
    files, stamps = snapshot(root)
    reviewed_hash = sha256(_canonical(files))
    # Re-read the same Git selection and bytes before the first artifact is created.
    assert_unchanged(root, files, stamps)
    _no_links(destination); destination.mkdir(parents=True, exist_ok=False)
    archive = write_archive(root, destination, files, stamps)
    assert_unchanged(root, files, stamps)
    _no_links(destination)
    with tarfile.open(archive, mode='r:gz') as tar:
        members = tar.getmembers()
        if [m.name for m in members] != [p['path'] for p in files] or not all(m.isfile() for m in members):
            raise SourceReviewError('Review archive member inventory differs')
        for member, expected in zip(members, files):
            if member.size != expected['bytes'] or sha256(tar.extractfile(member).read()) != expected['sha256']:
                raise SourceReviewError('Review archive payload differs')
    manifest = dict(formatVersion=1, state='READY_FOR_LOCAL_REVIEW',
                    createdAt=datetime.now(timezone.utc).isoformat(),
                    selection='Git cached plus untracked working-tree files, exclude-standard; not staged blob contents',
                    sourceRoot=str(root), sourceTreeSha256=reviewed_hash,
                    fileCount=len(files), totalBytes=sum(p['bytes'] for p in files), files=files,
                    archive=dict(path=archive.name, bytes=archive.stat().st_size, sha256=sha256(archive.read_bytes())),
                    sourceRepositoryCommitCreated=False, remoteCallsPerformed=False,
                    limitations='Bounded source-publication guard, not complete secret detection or license clearance; subsequent source changes require a new review.')
    assert_unchanged(root, files, stamps)
    _no_links(destination)
    pending = destination / 'manifest.pending.json'
    final = destination / 'manifest.json'
    with pending.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
        stream.flush(); os.fsync(stream.fileno())
    archive.chmod(0o444)
    pending.chmod(0o444)
    _no_links(final)
    if final.exists():
        raise SourceReviewError('Existing review artifacts must never be overwritten')
    pending.rename(final)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--directory', type=Path, required=True,
                        help='New Git-ignored directory under the selected project .local')
    args = parser.parse_args()
    try:
        manifest = prepare_review(args.root, args.directory)
    except (SourceReviewError, OSError, subprocess.SubprocessError, UnicodeError, tarfile.TarError) as error:
        parser.exit(1, 'Source review refused: ' + str(error) + '\nNo successful manifest was issued; retain any partial output for inspection.\n')
    print(json.dumps(dict(state=manifest['state'], files=manifest['fileCount'],
                          sourceTreeSha256=manifest['sourceTreeSha256'], archive=manifest['archive']), ensure_ascii=False))


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
