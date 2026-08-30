"""Load and integrity-check the normative V0.1 JSON Schema bundle."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
from pathlib import Path, PurePosixPath
from typing import Any

from . import strict_json


SCHEMA_BASE = "https://hermesmobile.dev/schemas/v0.1/"


class SchemaBundleError(RuntimeError):
    """Raised when checked-in protocol schemas are missing or inconsistent."""


def _sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def _bundle_digest(entries: list[tuple[str, str]]) -> str:
    material = b"".join(
        path.encode("utf-8") + b"\0" + digest.encode("ascii") + b"\n"
        for path, digest in entries
    )
    return _sha256(material)


@dataclass(frozen=True)
class SchemaBundle:
    root: Path
    version: str
    digest: str
    file_digests: dict[str, str]
    schemas: dict[str, dict[str, Any]]

    @classmethod
    def builtin(cls) -> "SchemaBundle":
        return cls.load(Path(__file__).with_name("schemas") / "v0.1")

    @classmethod
    def load(cls, root: Path) -> "SchemaBundle":
        root = root.resolve()
        manifest_path = root / "manifest.json"
        try:
            manifest = strict_json.loads(manifest_path.read_bytes())
        except OSError as exc:
            raise SchemaBundleError(f"cannot read schema manifest: {exc}") from exc
        except strict_json.StrictJsonError as exc:
            raise SchemaBundleError(f"invalid schema manifest: {exc}") from exc
        if not isinstance(manifest, dict) or set(manifest) != {
            "bundle_digest",
            "digest_algorithm",
            "files",
            "protocol_version",
        }:
            raise SchemaBundleError("schema manifest is not a closed V0.1 manifest")
        if manifest["digest_algorithm"] != "SHA-256":
            raise SchemaBundleError("unsupported schema manifest digest algorithm")
        if manifest["protocol_version"] != "0.1.0":
            raise SchemaBundleError("unexpected schema manifest protocol version")
        files = manifest["files"]
        if not isinstance(files, list) or not files:
            raise SchemaBundleError("schema manifest must list files")

        entries: list[tuple[str, str]] = []
        schemas: dict[str, dict[str, Any]] = {}
        seen_paths: set[str] = set()
        seen_ids: set[str] = set()
        for entry in files:
            if not isinstance(entry, dict) or set(entry) != {"digest", "path"}:
                raise SchemaBundleError("schema manifest file entry is not closed")
            path = entry["path"]
            digest = entry["digest"]
            if not isinstance(path, str) or not isinstance(digest, str):
                raise SchemaBundleError(
                    "schema manifest paths and digests must be strings"
                )
            pure = PurePosixPath(path)
            if pure.is_absolute() or ".." in pure.parts or pure.suffix != ".json":
                raise SchemaBundleError(f"unsafe schema manifest path: {path}")
            if path in seen_paths:
                raise SchemaBundleError(f"duplicate schema manifest path: {path}")
            seen_paths.add(path)
            schema_path = root.joinpath(*pure.parts)
            try:
                raw = schema_path.read_bytes()
            except OSError as exc:
                raise SchemaBundleError(f"cannot read schema {path}: {exc}") from exc
            if _sha256(raw) != digest:
                raise SchemaBundleError(f"schema digest mismatch: {path}")
            try:
                schema = strict_json.loads(raw)
            except strict_json.StrictJsonError as exc:
                raise SchemaBundleError(f"invalid schema JSON: {path}") from exc
            if not isinstance(schema, dict):
                raise SchemaBundleError(f"schema must be an object: {path}")
            schema_id = schema.get("$id")
            if not isinstance(schema_id, str) or not schema_id.startswith(SCHEMA_BASE):
                raise SchemaBundleError(f"schema has invalid $id: {path}")
            if schema_id in seen_ids:
                raise SchemaBundleError(f"duplicate schema $id: {schema_id}")
            seen_ids.add(schema_id)
            schemas[path] = schema
            entries.append((path, digest))

        if entries != sorted(entries):
            raise SchemaBundleError("schema manifest file entries must be sorted")
        digest = _bundle_digest(entries)
        if digest != manifest["bundle_digest"]:
            raise SchemaBundleError("schema bundle digest mismatch")
        return cls(
            root=root,
            version=manifest["protocol_version"],
            digest=digest,
            file_digests=dict(entries),
            schemas=schemas,
        )

    def validator(self, path: str):
        """Build a Draft 2020-12 validator with an offline schema registry."""

        try:
            from jsonschema import Draft202012Validator, FormatChecker
            from referencing import Registry, Resource
        except ImportError as exc:
            raise SchemaBundleError(
                "the mobile protocol codec requires the 'mobile' dependency extra"
            ) from exc
        try:
            schema = self.schemas[path]
        except KeyError as exc:
            raise SchemaBundleError(f"unknown protocol schema: {path}") from exc
        registry = Registry().with_resources([
            (item["$id"], Resource.from_contents(item))
            for item in self.schemas.values()
        ])
        Draft202012Validator.check_schema(schema)
        return Draft202012Validator(
            schema,
            registry=registry,
            format_checker=FormatChecker(),
        )
