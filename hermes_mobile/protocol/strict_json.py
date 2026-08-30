"""Strict bounded JSON parsing for the Mobile Agent Protocol."""

from __future__ import annotations

import json
import math
from typing import Any


MAX_DOCUMENT_BYTES = 1_048_576
MAX_DEPTH = 64
MAX_CONTAINER_ITEMS = 4096
MAX_SAFE_INTEGER = 9_007_199_254_740_991


class StrictJsonError(ValueError):
    """Raised when wire JSON is ambiguous, unsafe, or outside codec bounds."""


def _object_without_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise StrictJsonError(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def _safe_integer(raw: str) -> int:
    value = int(raw)
    if abs(value) > MAX_SAFE_INTEGER:
        raise StrictJsonError("JSON integer exceeds the interoperable safe range")
    return value


def _finite_float(raw: str) -> float:
    value = float(raw)
    if not math.isfinite(value):
        raise StrictJsonError("non-finite JSON numbers are forbidden")
    return value


def _reject_constant(raw: str) -> None:
    raise StrictJsonError(f"non-finite JSON number is forbidden: {raw}")


def _check_bounds(value: Any, depth: int = 0) -> None:
    if depth > MAX_DEPTH:
        raise StrictJsonError("JSON nesting exceeds the protocol limit")
    if isinstance(value, dict):
        if len(value) > MAX_CONTAINER_ITEMS:
            raise StrictJsonError("JSON object exceeds the protocol item limit")
        for key, item in value.items():
            if not isinstance(key, str):
                raise StrictJsonError("JSON object keys must be strings")
            _check_bounds(item, depth + 1)
    elif isinstance(value, list):
        if len(value) > MAX_CONTAINER_ITEMS:
            raise StrictJsonError("JSON array exceeds the protocol item limit")
        for item in value:
            _check_bounds(item, depth + 1)
    elif isinstance(value, int) and not isinstance(value, bool):
        if abs(value) > MAX_SAFE_INTEGER:
            raise StrictJsonError("JSON integer exceeds the interoperable safe range")
    elif isinstance(value, float) and not math.isfinite(value):
        raise StrictJsonError("non-finite JSON numbers are forbidden")


def loads(payload: bytes | str) -> Any:
    """Decode one UTF-8 JSON document and reject ambiguous representations."""

    if isinstance(payload, bytes):
        if len(payload) > MAX_DOCUMENT_BYTES:
            raise StrictJsonError("JSON document exceeds the protocol byte limit")
        try:
            text = payload.decode("utf-8", errors="strict")
        except UnicodeDecodeError as exc:
            raise StrictJsonError("protocol JSON must be valid UTF-8") from exc
    elif isinstance(payload, str):
        try:
            size = len(payload.encode("utf-8", errors="strict"))
        except UnicodeEncodeError as exc:
            raise StrictJsonError(
                "protocol JSON contains an invalid Unicode scalar"
            ) from exc
        if size > MAX_DOCUMENT_BYTES:
            raise StrictJsonError("JSON document exceeds the protocol byte limit")
        text = payload
    else:
        raise TypeError("protocol JSON must be bytes or str")

    try:
        value = json.loads(
            text,
            object_pairs_hook=_object_without_duplicates,
            parse_int=_safe_integer,
            parse_float=_finite_float,
            parse_constant=_reject_constant,
        )
    except StrictJsonError:
        raise
    except (TypeError, ValueError, json.JSONDecodeError) as exc:
        raise StrictJsonError(f"invalid protocol JSON: {exc}") from exc
    _check_bounds(value)
    return value


def dumps(value: Any) -> bytes:
    """Encode deterministic UTF-8 JSON after applying protocol bounds."""

    _check_bounds(value)
    try:
        encoded = json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8", errors="strict")
    except (TypeError, ValueError, UnicodeEncodeError) as exc:
        raise StrictJsonError(
            f"value cannot be encoded as protocol JSON: {exc}"
        ) from exc
    if len(encoded) > MAX_DOCUMENT_BYTES:
        raise StrictJsonError("JSON document exceeds the protocol byte limit")
    return encoded
