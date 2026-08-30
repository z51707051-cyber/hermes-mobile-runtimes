from __future__ import annotations

from pathlib import Path
import shutil

import pytest

from hermes_mobile.protocol import SchemaBundle, SchemaBundleError


def test_builtin_bundle_is_complete_and_meta_valid() -> None:
    bundle = SchemaBundle.builtin()

    assert bundle.version == "0.1.0"
    assert bundle.digest.startswith("sha256:")
    assert len([path for path in bundle.schemas if path.startswith("tools/")]) == 13
    for path in bundle.schemas:
        bundle.validator(path)


def test_bundle_rejects_tampered_schema(tmp_path: Path) -> None:
    source = SchemaBundle.builtin().root
    target = tmp_path / "v0.1"
    shutil.copytree(source, target)
    schema = target / "tools" / "phone.tap.schema.json"
    schema.write_bytes(schema.read_bytes() + b"\n")

    with pytest.raises(SchemaBundleError, match="digest mismatch"):
        SchemaBundle.load(target)
