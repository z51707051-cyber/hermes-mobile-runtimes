from __future__ import annotations

import copy

import pytest

from hermes_mobile.protocol import ProtocolCodec, ProtocolValidationError


def test_all_valid_golden_messages_round_trip(fixture_root) -> None:
    codec = ProtocolCodec()

    for path in sorted((fixture_root / "valid").glob("*.json")):
        message = codec.decode(path.read_bytes())
        assert codec.decode(codec.encode(message)) == message


def test_ambiguous_and_open_messages_fail_closed(fixture_root) -> None:
    codec = ProtocolCodec()

    for path in sorted((fixture_root / "invalid").glob("*.json")):
        with pytest.raises(ProtocolValidationError):
            codec.decode(path.read_bytes())


def test_all_tool_parameter_schemas_have_positive_and_negative_fixtures(
    load_fixture,
) -> None:
    codec = ProtocolCodec()
    request = load_fixture("valid/tool-execution-request.json")

    for tool, examples in load_fixture("all-tool-parameters.json").items():
        valid = copy.deepcopy(request)
        valid["tool"] = tool
        valid["parameters"] = examples["valid"]
        if tool in {"phone.tap", "phone.long_press", "phone.type", "phone.swipe"}:
            valid["state_precondition"] = {
                "state_id": "state-1",
                "maximum_age_ms": 1000,
            }
        codec.encode(valid)

        invalid = copy.deepcopy(valid)
        invalid["parameters"] = examples["invalid"]
        with pytest.raises(ProtocolValidationError):
            codec.encode(invalid)


def test_state_binding_deadline_and_recoverability_are_semantic_contracts(
    load_fixture,
) -> None:
    codec = ProtocolCodec()
    request = load_fixture("valid/tool-execution-request.json")
    request.update({
        "tool": "phone.tap",
        "parameters": {"target": {"state_id": "state-1", "node_id": "node-1"}},
        "state_precondition": None,
    })
    with pytest.raises(ProtocolValidationError, match="state_precondition"):
        codec.encode(request)

    result = load_fixture("valid/tool-execution-result.json")
    result["execution_status"] = "FAILED"
    result["recoverable"] = False
    result["error"] = {
        "code": "NODE_NOT_FOUND",
        "category": "EXECUTION",
        "owner": "CAPABILITY",
        "message": "node changed",
        "retry_disposition": "REOBSERVE",
    }
    with pytest.raises(ProtocolValidationError, match="recoverable"):
        codec.encode(result)


def test_authorized_action_digest_binds_normalized_fields(load_fixture) -> None:
    codec = ProtocolCodec()
    action = load_fixture("valid/authorized-action.json")

    action["device_id"] = "device-0002"

    with pytest.raises(ProtocolValidationError, match="action_digest"):
        codec.encode(action)
