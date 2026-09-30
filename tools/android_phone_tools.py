"""Hermes Agent registration shim for the process-local Android tool bridge.

The model sees provider-safe underscore names. Every handler maps to one
closed ``phone.*`` protocol capability and forwards only JSON arguments to an
object supplied by the Android host for the current user task. This module
cannot create an authorization session or obtain an Android context itself.
"""

from __future__ import annotations

import json
import logging
from threading import RLock
from typing import Any, Protocol

from tools.registry import no_cache_check_fn, registry, tool_error


logger = logging.getLogger(__name__)


class AndroidToolTransport(Protocol):
    def execute(self, canonical_tool: str, parameters_json: str) -> str:
        """Execute one canonical tool in the Android host and return JSON."""


_transport: AndroidToolTransport | None = None
_transport_lock = RLock()


MODEL_TO_CANONICAL = {
    "phone_read_screen": "phone.read_screen",
    "phone_screenshot": "phone.screenshot",
    "phone_tap": "phone.tap",
    "phone_long_press": "phone.long_press",
    "phone_type": "phone.type",
    "phone_swipe": "phone.swipe",
    "phone_back": "phone.back",
    "phone_home": "phone.home",
    "phone_open_app": "phone.open_app",
    "phone_wait": "phone.wait",
    "phone_notifications": "phone.notifications",
    "phone_current_app": "phone.current_app",
    "phone_device_state": "phone.device_state",
}


def configure_android_tool_transport(transport: AndroidToolTransport) -> None:
    """Install the host-owned transport before constructing ``AIAgent``."""
    if transport is None or not callable(getattr(transport, "execute", None)):
        raise TypeError("Android transport must expose execute(tool, parameters_json)")
    global _transport
    with _transport_lock:
        _transport = transport


def clear_android_tool_transport() -> None:
    """Revoke the current process-local task transport."""
    global _transport
    with _transport_lock:
        _transport = None


@no_cache_check_fn
def _android_transport_available() -> bool:
    with _transport_lock:
        return _transport is not None


def _dispatch(model_tool: str, arguments: dict[str, Any]) -> str:
    canonical_tool = MODEL_TO_CANONICAL.get(model_tool)
    if canonical_tool is None:
        return tool_error("Unknown Android phone capability", code="ACTION_REJECTED")
    if not isinstance(arguments, dict):
        return tool_error("Android phone arguments must be an object", code="ACTION_REJECTED")
    with _transport_lock:
        transport = _transport
    if transport is None:
        return tool_error(
            "Android task authorization is unavailable or expired",
            code="CAPABILITY_UNAVAILABLE",
        )
    try:
        parameters_json = json.dumps(
            arguments,
            ensure_ascii=False,
            separators=(",", ":"),
            allow_nan=False,
        )
        result = transport.execute(canonical_tool, parameters_json)
    except Exception:
        logger.exception("Android phone bridge rejected %s", canonical_tool)
        return tool_error("Android phone action was rejected", code="ACTION_REJECTED")
    if not isinstance(result, str):
        return tool_error("Android phone bridge returned a non-text result", code="ACTION_REJECTED")
    try:
        decoded = json.loads(result)
    except (TypeError, ValueError):
        return tool_error("Android phone bridge returned invalid JSON", code="ACTION_REJECTED")
    if not isinstance(decoded, dict):
        return tool_error("Android phone bridge returned a non-object result", code="ACTION_REJECTED")
    return json.dumps(decoded, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


_POINT = {
    "type": "object",
    "properties": {
        "state_id": {"type": "string"},
        "x_px": {"type": "integer", "minimum": 0, "maximum": 32767},
        "y_px": {"type": "integer", "minimum": 0, "maximum": 32767},
    },
    "required": ["state_id", "x_px", "y_px"],
    "additionalProperties": False,
}
_TARGET = {
    "oneOf": [
        {
            "type": "object",
            "properties": {
                "state_id": {"type": "string"},
                "node_id": {"type": "string"},
            },
            "required": ["state_id", "node_id"],
            "additionalProperties": False,
        },
        _POINT,
    ]
}
_NODE_TARGET = _TARGET["oneOf"][0]


def _schema(description: str, properties: dict[str, Any] | None = None, required: list[str] | None = None) -> dict[str, Any]:
    parameters: dict[str, Any] = {
        "type": "object",
        "properties": properties or {},
        "additionalProperties": False,
    }
    if required:
        parameters["required"] = required
    return {"description": description, "parameters": parameters}


_COMMON = {"toolset": "mobile", "check_fn": _android_transport_available, "emoji": "📱"}


registry.register(
    name="phone_read_screen", **_COMMON,
    schema=_schema("Read the active Android screen as bounded semantic UI nodes.", {
        "scope": {"type": "string", "enum": ["ACTIVE_WINDOW"]},
        "max_nodes": {"type": "integer", "minimum": 1, "maximum": 500},
        "max_text_chars": {"type": "integer", "minimum": 1, "maximum": 20000},
    }),
    handler=lambda args, **_: _dispatch("phone_read_screen", args),
)
registry.register(
    name="phone_screenshot", **_COMMON,
    schema=_schema("Capture a protected screenshot artifact from the active Android display.", {
        "display_id": {"type": "integer", "minimum": 0, "maximum": 7},
        "format": {"type": "string", "enum": ["PNG", "WEBP"]},
        "crop": {"type": "object", "properties": {
            "x_px": {"type": "integer", "minimum": 0, "maximum": 32767},
            "y_px": {"type": "integer", "minimum": 0, "maximum": 32767},
            "width_px": {"type": "integer", "minimum": 1, "maximum": 32768},
            "height_px": {"type": "integer", "minimum": 1, "maximum": 32768},
        }, "required": ["x_px", "y_px", "width_px", "height_px"], "additionalProperties": False},
    }),
    handler=lambda args, **_: _dispatch("phone_screenshot", args),
)
registry.register(
    name="phone_tap", **_COMMON,
    schema=_schema("Tap a target from the latest Android screen observation.", {"target": _TARGET}, ["target"]),
    handler=lambda args, **_: _dispatch("phone_tap", args),
)
registry.register(
    name="phone_long_press", **_COMMON,
    schema=_schema("Long-press a target from the latest Android screen observation.", {
        "target": _TARGET,
        "duration_ms": {"type": "integer", "minimum": 300, "maximum": 5000},
    }, ["target", "duration_ms"]),
    handler=lambda args, **_: _dispatch("phone_long_press", args),
)
registry.register(
    name="phone_type", **_COMMON,
    schema=_schema("Type into an explicit editable node from the latest Android screen observation.", {
        "text": {"type": "string", "minLength": 1, "maxLength": 10000},
        "mode": {"type": "string", "enum": ["APPEND", "REPLACE"]},
        "target": _NODE_TARGET,
    }, ["text", "mode", "target"]),
    handler=lambda args, **_: _dispatch("phone_type", args),
)
registry.register(
    name="phone_swipe", **_COMMON,
    schema=_schema("Swipe between two points bound to the latest Android screen state.", {
        "start": _POINT,
        "end": _POINT,
        "duration_ms": {"type": "integer", "minimum": 100, "maximum": 5000},
    }, ["start", "end", "duration_ms"]),
    handler=lambda args, **_: _dispatch("phone_swipe", args),
)
registry.register(
    name="phone_back", **_COMMON,
    schema=_schema("Press Android Back."),
    handler=lambda args, **_: _dispatch("phone_back", args),
)
registry.register(
    name="phone_home", **_COMMON,
    schema=_schema("Press Android Home."),
    handler=lambda args, **_: _dispatch("phone_home", args),
)
registry.register(
    name="phone_open_app", **_COMMON,
    schema=_schema("Open an installed Android app by exact package name.", {
        "package": {"type": "string", "pattern": r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$", "maxLength": 255},
    }, ["package"]),
    handler=lambda args, **_: _dispatch("phone_open_app", args),
)
registry.register(
    name="phone_wait", **_COMMON,
    schema=_schema("Wait for time or a bounded observable Android condition.", {
        "timeout_ms": {"type": "integer", "minimum": 1, "maximum": 30000},
        "condition": {"type": "object", "properties": {
            "kind": {"type": "string", "enum": ["STATE_CHANGED", "FOREGROUND_APP_IS", "TEXT_PRESENT"]},
            "expected": {"type": "string", "maxLength": 4096},
        }, "required": ["kind"], "additionalProperties": False},
    }, ["timeout_ms"]),
    handler=lambda args, **_: _dispatch("phone_wait", args),
)
registry.register(
    name="phone_notifications", **_COMMON,
    schema=_schema("Read a bounded page of Android notifications after notification access is granted.", {
        "cursor": {"type": "string"},
        "limit": {"type": "integer", "minimum": 1, "maximum": 100},
        "source_packages": {"type": "array", "items": {"type": "string"}, "uniqueItems": True, "maxItems": 20},
    }),
    handler=lambda args, **_: _dispatch("phone_notifications", args),
)
registry.register(
    name="phone_current_app", **_COMMON,
    schema=_schema("Read the currently observed foreground Android app."),
    handler=lambda args, **_: _dispatch("phone_current_app", args),
)
registry.register(
    name="phone_device_state", **_COMMON,
    schema=_schema("Read selected non-secret Android device state fields.", {
        "fields": {"type": "array", "items": {"type": "string", "enum": [
            "BATTERY", "CHARGING", "WIFI", "BLUETOOTH", "NETWORK", "SCREEN", "LOCALE",
        ]}, "uniqueItems": True, "minItems": 1, "maxItems": 7},
    }),
    handler=lambda args, **_: _dispatch("phone_device_state", args),
)
