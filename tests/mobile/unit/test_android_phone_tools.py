from __future__ import annotations

import base64
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


class ArtifactAndroidTransport:
    def __init__(self, result: dict, content: bytes) -> None:
        self.result = result
        self.content = content
        self.consumed: list[str] = []

    def execute(self, canonical_tool: str, parameters_json: str) -> str:
        return json.dumps({**self.result, "tool": canonical_tool})

    def consumeArtifactForModel(self, artifact_id: str) -> str | None:
        self.consumed.append(artifact_id)
        return base64.b64encode(self.content).decode("ascii")


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

    result = json.loads(registry.dispatch("phone_wait", {"timeout_ms": 1}))

    assert transport.calls == [("phone.wait", {"timeout_ms": 1})]
    assert result == {"execution_status": "SUCCEEDED", "tool": "phone.wait"}


def test_revoked_transport_fails_without_dispatch() -> None:
    android_phone_tools.clear_android_tool_transport()

    result = json.loads(registry.dispatch("phone_current_app", {}))

    assert result["code"] == "CAPABILITY_UNAVAILABLE"
    assert "authorization" in result["error"].lower()


def test_read_screen_delivers_task_bound_semantic_nodes_to_agent() -> None:
    tree = {
        "schema_version": 1,
        "foreground_package": "ai.hermes.mobile.runtime",
        "node_count": 2,
        "truncated": False,
        "capture_errors": [],
        "redactions": ["PASSWORD_CONTENT_WITHHELD"],
        "nodes": [
            {"node_id": "node-1", "role": "BUTTON", "text": "选择文件或图片"},
            {"node_id": "node-2", "role": "TEXT_FIELD", "text": None, "password": True},
        ],
    }
    content = json.dumps(tree, ensure_ascii=False, separators=(",", ":")).encode()
    transport = ArtifactAndroidTransport(
        result=_artifact_result(
            media_type="application/vnd.hermes.ui-tree+json",
            size_bytes=len(content),
        ),
        content=content,
    )
    android_phone_tools.configure_android_tool_transport(transport)

    result = json.loads(registry.dispatch("phone_read_screen", {}))

    assert transport.consumed == ["artifact-model"]
    assert result["model_content_status"] == "DELIVERED_ONCE"
    assert result["semantic_ui"]["state_id"] == "state-model"
    assert result["semantic_ui"]["content_trust"] == "UNTRUSTED_UI_CONTENT"
    assert result["semantic_ui"]["nodes"][0]["text"] == "选择文件或图片"
    assert result["semantic_ui"]["nodes"][1]["text"] is None


def test_screenshot_delivers_multimodal_image_once_to_vision_pipeline() -> None:
    image = b"\x89PNG\r\n\x1a\nmodel-image"
    transport = ArtifactAndroidTransport(
        result=_artifact_result(media_type="image/png", size_bytes=len(image)),
        content=image,
    )
    android_phone_tools.configure_android_tool_transport(transport)

    result = registry.dispatch("phone_screenshot", {})

    assert transport.consumed == ["artifact-model"]
    assert result["_multimodal"] is True
    assert result["content"][1]["type"] == "image_url"
    assert result["content"][1]["image_url"]["url"] == (
        "data:image/png;base64," + base64.b64encode(image).decode("ascii")
    )
    assert "phone_read_screen" in result["text_summary"]


def test_notifications_deliver_redacted_records_to_agent() -> None:
    observation = {
        "schema_version": 1,
        "mode": "ACTIVE",
        "cursor": "notifications:session:1",
        "has_more": False,
        "records": [
            {
                "source_package": "com.tencent.mm",
                "title": "联系人",
                "text": "测试消息",
            }
        ],
    }
    content = json.dumps(
        observation, ensure_ascii=False, separators=(",", ":")
    ).encode()
    transport = ArtifactAndroidTransport(
        result=_artifact_result(
            media_type="application/vnd.hermes.notifications+json",
            size_bytes=len(content),
        ),
        content=content,
    )
    android_phone_tools.configure_android_tool_transport(transport)

    result = json.loads(registry.dispatch("phone_notifications", {}))

    assert result["notifications"]["records"][0]["text"] == "测试消息"
    assert result["notifications"]["content_trust"] == "UNTRUSTED_DEVICE_CONTENT"
    assert result["model_content_status"] == "DELIVERED_ONCE"


def _artifact_result(*, media_type: str, size_bytes: int) -> dict:
    return {
        "execution_status": "SUCCEEDED",
        "after_state": {
            "state_id": "state-model",
            "package_name": "ai.hermes.mobile.runtime",
        },
        "artifacts": [
            {
                "artifact_id": "artifact-model",
                "media_type": media_type,
                "size_bytes": size_bytes,
            }
        ],
    }
