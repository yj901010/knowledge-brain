"""Keep the isolated Graph Web build's draft mirror byte-identical to its source."""
from pathlib import Path
import argparse


def sync(root: Path, write: bool = False) -> None:
    root = root.resolve()
    source = root / "data/public-curation/draft-pack-v1.json"
    target = root / "apps/graph-web/app/public-curation-pack.json"
    for path in (source, target):
        if path.is_symlink() or getattr(path, "is_junction", lambda: False)() or not path.resolve().is_relative_to(root):
            raise RuntimeError("Public curation mirror path must stay within the project")
    with source.open("rb") as stream:
        expected = stream.read(131073)
    if len(expected) > 131072:
        raise RuntimeError("Public curation source exceeds the 128 KiB authoring bound")
    if write:
        target.write_bytes(expected)
    if not target.is_file() or target.read_bytes() != expected:
        raise RuntimeError("Public curation web draft mirror is stale; run scripts/Sync-PublicCurationWeb.py --sync")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sync", action="store_true", help="Refresh the checked-in web mirror before verifying bytes")
    args = parser.parse_args()
    sync(Path(__file__).resolve().parents[1], args.sync)
    print("PASS public curation web mirror matches source bytes")
