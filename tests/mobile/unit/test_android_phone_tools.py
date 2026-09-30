from __future__ import annotations

import json

from tools import android_phone_tools
from tools.registry import registry


class FakeAndroidTransport:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []

    def execute(self, canonical_tool: str, parameters_json: str) -> str:
        parameters = json.loads(parameters_json)
        self.calls.append((canonical_tool, parameters))
        return json.dumps({"execution_status": "SUCCEEDED", "tool": canonical_tool})


def teardown_function() -> None:
    android_phone_tools.clear_android_tool_transport()


def test_mobile_tools_are_hidden_until_android_supplies_task_transport() -> None:
    names = set(android_phone_tools.MODEL_TO_CANONICAL)
    android_phone_tools.clear_android_tool_transport()

    assert registry.get_definitions(names, quiet=True) == []

    android_phone_tools.configure_android_tool_transport(FakeAndroidTransport())
    definitions = registry.get_definitions(names, quiet=True)

    assert {item["function"]["name"] for item in definitions} == names
    assert all(item["function"]["name"].startswith("phone_") for item in definitions)


def test_model_tool_maps_to_one_closed_canonical_android_capability() -> None:
    transport = FakeAndroidTransport()
    android_phone_tools.configure_android_tool_transport(transport)

    result = json.loads(
        registry.dispatch("phone_wait", {"timeout_ms": 1})
    )

    assert transport.calls == [("phone.wait", {"timeout_ms": 1})]
    assert result == {"execution_status": "SUCCEEDED", "tool": "phone.wait"}


def test_revoked_transport_fails_without_dispatch() -> None:
    android_phone_tools.clear_android_tool_transport()

    result = json.loads(registry.dispatch("phone_current_app", {}))

    assert result["code"] == "CAPABILITY_UNAVAILABLE"
    assert "authorization" in result["error"].lower()
