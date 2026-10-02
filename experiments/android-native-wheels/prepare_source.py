#!/usr/bin/env python3
"""Download and safely extract one hash-pinned package source archive."""

from __future__ import annotations

import argparse
from hashlib import sha256
import json
from pathlib import Path
import tarfile
from urllib.request import Request, urlopen


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("package")
    parser.add_argument("destination", type=Path)
    parser.add_argument("--manifest", type=Path, default=Path(__file__).with_name("sources.json"))
    args = parser.parse_args()
    source = json.loads(args.manifest.read_text())[args.package]
    request = Request(source["url"], headers={"User-Agent": "Hermes-Android-Wheel-Builder/1"})
    with urlopen(request, timeout=60) as response:
        archive = response.read(source["size"] + 1)
    if len(archive) != source["size"]:
        raise RuntimeError("source archive size does not match the locked manifest")
    if sha256(archive).hexdigest() != source["sha256"]:
        raise RuntimeError("source archive hash does not match the locked manifest")
    args.destination.mkdir(parents=True, exist_ok=True)
    archive_path = args.destination / f"{args.package}.tar.gz"
    archive_path.write_bytes(archive)
    extract_root = args.destination / "source"
    extract_root.mkdir()
    with tarfile.open(archive_path, "r:gz") as package:
        package.extractall(extract_root, filter="data")
    roots = [path for path in extract_root.iterdir() if path.is_dir()]
    if len(roots) != 1:
        raise RuntimeError("source archive must have exactly one root directory")
    print(roots[0])


if __name__ == "__main__":
    main()
