from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path

import pytest

from hermes_mobile.protocol import ProtocolCodec, action_digest, sha256_digest
from hermes_mobile.runtime import (
    CANONICAL_CAPABILITIES,
    CapabilityRegistry,
    CapabilityReport,
    CapabilityUnavailableError,
    RouteAuditRecord,
    ToolRouteError,
    ToolRouter,
)
from hermes_mobile.runtime.capability_registry import CapabilityRegistryError


FIXTURES = Path(__file__).parents[1] / "contract" / "fixtures" / "v0.1"


def _fixture(relative: str) -> dict:
    return json.loads((FIXTURES / relative).read_text(encoding="utf-8"))


def _registry(tool: str = "phone.current_app") -> CapabilityRegistry:
    registry = CapabilityRegistry()
    registry.replace_snapshot(
        device_id="device-0001",
        generation=1,
        reports=[CapabilityReport(tool, True, "fake.provider")],
    )
    return registry


def _device_result(request: dict, *, decision_id: str = "decision-0001") -> dict:
    result = _fixture("valid/tool-execution-result.json")
    for field in (
        "protocol_version",
        "request_id",
        "task_id",
        "span_id",
        "device_id",
        "tool",
        "parameters",
        "attempt",
        "idempotency_key",
    ):
        result[field] = deepcopy(request[field])
    result["parameter_digest"] = sha256_digest(request["parameters"])
    result["permission_decision_id"] = decision_id
    return result


class FakePolicyBroker:
    def __init__(self, response: bytes) -> None:
        self.response = response
        self.calls: list[bytes] = []

    def evaluate(self, request: bytes) -> bytes:
        self.calls.append(request)
        return self.response


class FakeDeviceTransport:
    def __init__(self, response: bytes) -> None:
        self.response = response
        self.calls: list[bytes] = []

    def execute(self, authorized_action: bytes) -> bytes:
        self.calls.append(authorized_action)
        return self.response


class FakeAuditSink:
    def __init__(self) -> None:
        self.records: list[RouteAuditRecord] = []
        self.failure: Exception | None = None

    def append(self, record: RouteAuditRecord) -> None:
        if self.failure is not None:
            raise self.failure
        self.records.append(record)


def _router(
    *,
    request: dict | None = None,
    action: dict | None = None,
    result: dict | None = None,
    registry: CapabilityRegistry | None = None,
) -> tuple[
    ToolRouter,
    FakePolicyBroker,
    FakeDeviceTransport,
    FakeAuditSink,
    ProtocolCodec,
]:
    codec = ProtocolCodec()
    request = request or _fixture("valid/tool-execution-request.json")
    action = action or _fixture("valid/authorized-action.json")
    result = result or _device_result(request)
    broker = FakePolicyBroker(codec.encode(action))
    device = FakeDeviceTransport(codec.encode(result))
    audit = FakeAuditSink()
    return (
        ToolRouter(
            capabilities=registry or _registry(request["tool"]),
            policy_broker=broker,
            device_transport=device,
            audit_sink=audit,
            codec=codec,
        ),
        broker,
        device,
        audit,
        codec,
    )


def test_catalog_matches_the_normative_tool_schema_bundle() -> None:
    codec = ProtocolCodec()
    schema_tools = {
        path.removeprefix("tools/").removesuffix(".schema.json")
        for path in codec.bundle.schemas
        if path.startswith("tools/")
    }

    assert {definition.tool for definition in CANONICAL_CAPABILITIES} == schema_tools
    assert (
        next(
            item for item in CANONICAL_CAPABILITIES if item.tool == "phone.type"
        ).minimum_risk
        == "L2"
    )


def test_registry_is_monotonic_closed_and_fail_closed() -> None:
    registry = _registry()

    with pytest.raises(CapabilityRegistryError, match="increase monotonically"):
        registry.replace_snapshot(
            device_id="device-0001",
            generation=1,
            reports=[CapabilityReport("phone.current_app", True, "fake.provider")],
        )
    with pytest.raises(CapabilityRegistryError, match="unknown canonical"):
        CapabilityReport("phone.raw_shell", True, "fake.provider")
    with pytest.raises(CapabilityUnavailableError):
        registry.require_available("device-0001", "phone.notifications")


def test_router_dispatches_only_the_broker_authorized_action() -> None:
    request = _fixture("valid/tool-execution-request.json")
    router, broker, device, audit, codec = _router(request=request)

    result = codec.decode(router.route(codec.encode(request)))

    assert result["execution_status"] == "SUCCEEDED"
    assert len(broker.calls) == 1
    assert len(device.calls) == 1
    assert codec.decode(device.calls[0])["message_type"] == "action.authorized"
    assert [record.stage for record in audit.records] == ["AUTHORIZED", "RESULT"]
    assert all(record.request_id == request["request_id"] for record in audit.records)


def test_planner_cannot_submit_an_authorized_action_directly() -> None:
    action = _fixture("valid/authorized-action.json")
    router, broker, device, audit, codec = _router()

    with pytest.raises(ToolRouteError, match="only ToolExecutionRequest") as raised:
        router.route(codec.encode(action))

    assert raised.value.code == "ACTION_REJECTED"
    assert broker.calls == []
    assert device.calls == []
    assert audit.records == []


def test_unavailable_capability_never_reaches_policy_or_device() -> None:
    request = _fixture("valid/tool-execution-request.json")
    router, broker, device, audit, codec = _router(registry=CapabilityRegistry())

    with pytest.raises(ToolRouteError) as raised:
        router.route(codec.encode(request))

    assert raised.value.code == "CAPABILITY_UNAVAILABLE"
    assert broker.calls == []
    assert device.calls == []
    assert audit.records == []


def test_policy_terminal_result_never_reaches_device() -> None:
    request = _fixture("valid/tool-execution-request.json")
    terminal = _device_result(request)
    terminal.update({
        "execution_status": "DENIED",
        "error": {
            "code": "PERMISSION_DENIED",
            "category": "POLICY",
            "owner": "POLICY",
            "message": "policy denied the action",
            "retry_disposition": "NEVER",
        },
        "recoverable": False,
    })
    router, broker, device, audit, codec = _router(request=request)
    broker.response = codec.encode(terminal)

    result = codec.decode(router.route(codec.encode(request)))

    assert result["execution_status"] == "DENIED"
    assert len(broker.calls) == 1
    assert device.calls == []
    assert [record.stage for record in audit.records] == ["RESULT"]
    assert audit.records[0].execution_status == "DENIED"


def test_broker_cannot_mutate_request_or_undercut_baseline_risk() -> None:
    request = _fixture("valid/tool-execution-request.json")
    action = _fixture("valid/authorized-action.json")
    action["span_id"] = "span-mutated"
    router, _, device, audit, codec = _router(request=request, action=action)

    with pytest.raises(ToolRouteError, match="changed request field") as changed:
        router.route(codec.encode(request))
    assert changed.value.owner == "POLICY"
    assert device.calls == []
    assert audit.records == []

    request["tool"] = "phone.open_app"
    request["parameters"] = {"package": "com.example.music"}
    action = _fixture("valid/authorized-action.json")
    for field in (
        "tool",
        "parameters",
        "state_precondition",
        "verification",
    ):
        action[field] = deepcopy(request[field])
    action["effective_risk"] = "L0"
    action["action_digest"] = action_digest(action)
    router, _, device, audit, codec = _router(request=request, action=action)

    with pytest.raises(ToolRouteError, match="below the capability baseline"):
        router.route(codec.encode(request))
    assert device.calls == []
    assert audit.records == []


def test_device_result_must_bind_request_and_policy_decision() -> None:
    request = _fixture("valid/tool-execution-request.json")
    result = _device_result(request, decision_id="decision-other")
    router, _, device, audit, codec = _router(request=request, result=result)

    with pytest.raises(ToolRouteError, match="permission decision mismatch") as raised:
        router.route(codec.encode(request))

    assert raised.value.owner == "ANDROID_PEP"
    assert len(device.calls) == 1
    assert [record.stage for record in audit.records] == ["AUTHORIZED"]


def test_audit_precommit_failure_prevents_device_execution() -> None:
    request = _fixture("valid/tool-execution-request.json")
    router, broker, device, audit, codec = _router(request=request)
    audit.failure = OSError("audit store unavailable")

    with pytest.raises(ToolRouteError, match="audit is unavailable") as raised:
        router.route(codec.encode(request))

    assert raised.value.code == "AUDIT_UNAVAILABLE"
    assert len(broker.calls) == 1
    assert device.calls == []


def test_result_audit_correlates_before_and_after_state_ids() -> None:
    request = _fixture("valid/tool-execution-request.json")
    result = _device_result(request)
    state = {
        "state_id": "state-current",
        "previous_state_id": "state-previous",
        "captured_at": "2026-08-30T10:00:01Z",
        "freshness_ms": 25,
        "device_id": request["device_id"],
        "foreground_package": "com.example.music",
        "foreground_activity": "com.example.music.PlayerActivity",
        "screen_fingerprint": {
            "basis": "WINDOW_IDENTITY",
            "digest": "sha256:" + "a" * 64,
        },
        "capture_status": "COMPLETE",
        "capture_errors": [],
        "transition": "NONE",
    }
    result["before_state"] = state
    result["after_state"] = state
    result["verification"]["observed_state_ids"] = [state["state_id"]]
    router, _, _, audit, codec = _router(request=request, result=result)

    router.route(codec.encode(request))

    completed = audit.records[-1]
    assert completed.before_state_id == state["state_id"]
    assert completed.after_state_id == state["state_id"]
    assert completed.parameter_digest == sha256_digest(request["parameters"])
