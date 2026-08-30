#!/usr/bin/env python3
"""Generate or verify the HMR V0.1 schema bundle manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ROOT = REPO_ROOT / "hermes_mobile" / "protocol" / "schemas" / "v0.1"


def _digest(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def build_manifest(root: Path) -> dict[str, object]:
    entries = [
        {
            "path": path.relative_to(root).as_posix(),
            "digest": _digest(path.read_bytes()),
        }
        for path in sorted(root.rglob("*.schema.json"))
    ]
    material = b"".join(
        entry["path"].encode("utf-8") + b"\0" + entry["digest"].encode("ascii") + b"\n"
        for entry in entries
    )
    return {
        "protocol_version": "0.1.0",
        "digest_algorithm": "SHA-256",
        "bundle_digest": _digest(material),
        "files": entries,
    }


def render(manifest: dict[str, object]) -> str:
    return json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    args = parser.parse_args()
    expected = render(build_manifest(args.root))
    path = args.root / "manifest.json"
    if args.check:
        try:
            actual = path.read_text(encoding="utf-8")
        except OSError as exc:
            print(f"cannot read {path}: {exc}", file=sys.stderr)
            return 1
        if actual != expected:
            print(f"schema manifest is stale: {path}", file=sys.stderr)
            return 1
        print(f"verified protocol schema manifest: {path}")
        return 0
    path.write_text(expected, encoding="utf-8")
    print(f"wrote protocol schema manifest: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
