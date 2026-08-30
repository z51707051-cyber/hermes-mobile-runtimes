"""RFC 8785-compatible canonicalization for the integer-only action domain."""

from __future__ import annotations

import hashlib
import json
from typing import Any

from .strict_json import MAX_DEPTH, MAX_SAFE_INTEGER, StrictJsonError


def _utf16_sort_key(value: str) -> bytes:
    try:
        return value.encode("utf-16-be", errors="strict")
    except UnicodeEncodeError as exc:
        raise StrictJsonError(
            "canonical JSON forbids unpaired Unicode surrogates"
        ) from exc


def _canonical(value: Any, depth: int) -> str:
    if depth > MAX_DEPTH:
        raise StrictJsonError("canonical JSON nesting exceeds the protocol limit")
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, int) and not isinstance(value, bool):
        if abs(value) > MAX_SAFE_INTEGER:
            raise StrictJsonError("canonical JSON integer exceeds the safe range")
        return str(value)
    if isinstance(value, float):
        raise StrictJsonError("security digests forbid floating-point values")
    if isinstance(value, str):
        _utf16_sort_key(value)
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if isinstance(value, list):
        return "[" + ",".join(_canonical(item, depth + 1) for item in value) + "]"
    if isinstance(value, dict):
        if not all(isinstance(key, str) for key in value):
            raise StrictJsonError("canonical JSON object keys must be strings")
        keys = sorted(value, key=_utf16_sort_key)
        return (
            "{"
            + ",".join(
                f"{_canonical(key, depth + 1)}:{_canonical(value[key], depth + 1)}"
                for key in keys
            )
            + "}"
        )
    raise StrictJsonError(f"unsupported canonical JSON type: {type(value).__name__}")


def canonical_json(value: Any) -> bytes:
    """Return canonical UTF-8 JSON for protocol security digests.

    Security-relevant V0.1 schemas intentionally use bounded integers rather
    than JSON floating-point values. Within that closed domain this output is
    RFC 8785 compatible and has identical Python/Kotlin golden fixtures.
    """

    return _canonical(value, 0).encode("utf-8")


def sha256_digest(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_json(value)).hexdigest()


ACTION_DIGEST_FIELDS = (
    "request_id",
    "task_id",
    "device_id",
    "tool",
    "parameters",
    "state_precondition",
    "verification",
    "idempotency_key",
    "deadline",
    "effective_target",
    "effective_risk",
)


def action_digest_material(message: dict[str, Any]) -> dict[str, Any]:
    """Return the exact V0.1 logical action protected by policy approval."""

    try:
        version = message["protocol_version"].split(".")
        material = {field: message[field] for field in ACTION_DIGEST_FIELDS}
    except (AttributeError, KeyError) as exc:
        raise StrictJsonError("authorized action is missing digest material") from exc
    if len(version) != 3:
        raise StrictJsonError("authorized action has an invalid protocol version")
    return {"protocol_line": ".".join(version[:2]), **material}


def action_digest(message: dict[str, Any]) -> str:
    return sha256_digest(action_digest_material(message))
