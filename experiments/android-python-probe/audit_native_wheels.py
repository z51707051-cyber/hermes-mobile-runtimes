#!/usr/bin/env python3
"""Inventory exact locked native dependencies without installing or downgrading.

This is evidence collection, not a resolver or proof of runtime compatibility.
An index failure is unknown, never evidence that no Android wheel exists.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from hashlib import sha256
from html.parser import HTMLParser
import json
from pathlib import Path
import tomllib
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urlsplit
from urllib.request import Request, urlopen


NATIVE_PACKAGES = {
    "cryptography", "jiter", "pillow", "psutil", "pydantic-core", "pyyaml",
    "rpds-py", "ruamel-yaml-clib",
}


class WheelLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.names = []

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            href = dict(attrs).get("href", "")
            filename = unquote(urlsplit(href).path.rsplit("/", 1)[-1])
            if filename.endswith(".whl"):
                self.names.append(filename)


def index_evidence(url, version, is_json):
    try:
        request = Request(url, headers={"User-Agent": "Hermes-Android-Feasibility/1"})
        with urlopen(request, timeout=15) as response:
            raw = response.read(8 * 1024 * 1024 + 1)
        if len(raw) > 8 * 1024 * 1024:
            raise ValueError("Index exceeds evidence size limit")
        if is_json:
            names = [entry["filename"] for entry in json.loads(raw)["urls"]]
        else:
            parser = WheelLinks()
            parser.feed(raw.decode("utf-8"))
            names = parser.names
        exact = [name for name in names if name.split("-")[1] == version]
        return {
            "url": url, "status": "observed", "sha256": sha256(raw).hexdigest(),
            "android_wheels_at_locked_version": sorted(
                name for name in exact if "-android_" in name
            ),
        }
    except (HTTPError, URLError, TimeoutError, ValueError, KeyError, OSError) as error:
        return {"url": url, "status": "unknown", "error_type": type(error).__name__}


def inventory(lock_path, online):
    raw = lock_path.read_bytes()
    packages = tomllib.loads(raw.decode("utf-8"))["package"]
    result = {
        "observed_at": datetime.now(timezone.utc).isoformat(),
        "lock_sha256": sha256(raw).hexdigest(),
        "scope": "selected_native_dependencies_only",
        "interpretation": "Wheel names are candidates, not import/build evidence.",
        "packages": [],
    }
    for package in packages:
        if package["name"] not in NATIVE_PACKAGES:
            continue
        name, version = package["name"], package["version"]
        filenames = [unquote(urlsplit(wheel["url"]).path.rsplit("/", 1)[-1])
                     for wheel in package.get("wheels", [])]
        row = {
            "name": name, "locked_version": version,
            "locked_android_wheels": [x for x in filenames if "-android_" in x],
            "indexes": [],
        }
        if online:
            with ThreadPoolExecutor(max_workers=2) as executor:
                checks = [
                    executor.submit(index_evidence, f"https://pypi.org/pypi/{name}/{version}/json", version, True),
                    executor.submit(index_evidence, f"https://chaquo.com/pypi-13.1/{name}/", version, False),
                ]
                row["indexes"] = [check.result() for check in checks]
        result["packages"].append(row)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=Path("uv.lock"))
    parser.add_argument("--online", action="store_true")
    args = parser.parse_args()
    print(json.dumps(inventory(args.lock, args.online), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
