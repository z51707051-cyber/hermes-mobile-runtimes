"""Schema-backed Mobile Agent Protocol message codec."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from .canonical import action_digest
from .schema_bundle import SchemaBundle
from . import strict_json


MESSAGE_SCHEMAS = {
    "compatibility.offer": "requests/compatibility-offer.schema.json",
    "compatibility.selection": "requests/compatibility-selection.schema.json",
    "tool.execution_request": "requests/tool-execution-request.schema.json",
    "action.authorized": "requests/authorized-action.schema.json",
    "tool.execution_result": "results/tool-execution-result.schema.json",
    "mobile.event": "events/mobile-event.schema.json",
}
STATE_BOUND_TOOLS = frozenset({
    "phone.tap",
    "phone.long_press",
    "phone.type",
    "phone.swipe",
})
RECOVERABLE_DISPOSITIONS = frozenset({"REOBSERVE", "RETRY_SAME_ACTION", "REPLAN"})


class ProtocolValidationError(ValueError):
    code = "PROTOCOL_INCOMPATIBLE"


def _timestamp(raw: str) -> datetime:
    value = raw[:-1] + "+00:00" if raw.endswith("Z") else raw
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None or parsed.utcoffset() != timezone.utc.utcoffset(parsed):
        raise ProtocolValidationError("protocol timestamps must use UTC")
    return parsed.astimezone(timezone.utc)


class ProtocolCodec:
    def __init__(self, bundle: SchemaBundle | None = None) -> None:
        self.bundle = bundle or SchemaBundle.builtin()
        self._validators = {
            message_type: self.bundle.validator(path)
            for message_type, path in MESSAGE_SCHEMAS.items()
        }
        self._tool_validators = {
            path.removeprefix("tools/").removesuffix(
                ".schema.json"
            ): self.bundle.validator(path)
            for path in self.bundle.schemas
            if path.startswith("tools/")
        }

    def decode(self, payload: bytes | str) -> dict[str, Any]:
        try:
            value = strict_json.loads(payload)
        except strict_json.StrictJsonError as exc:
            raise ProtocolValidationError(str(exc)) from exc
        if not isinstance(value, dict):
            raise ProtocolValidationError("protocol message must be a JSON object")
        message_type = value.get("message_type")
        if not isinstance(message_type, str) or message_type not in self._validators:
            raise ProtocolValidationError("unknown protocol message_type")
        self._validate(value, message_type)
        return value

    def encode(self, message: dict[str, Any]) -> bytes:
        message_type = message.get("message_type")
        if not isinstance(message_type, str) or message_type not in self._validators:
            raise ProtocolValidationError("unknown protocol message_type")
        self._validate(message, message_type)
        try:
            return strict_json.dumps(message)
        except strict_json.StrictJsonError as exc:
            raise ProtocolValidationError(str(exc)) from exc

    def _validate(self, message: dict[str, Any], message_type: str) -> None:
        errors = sorted(
            self._validators[message_type].iter_errors(message),
            key=lambda error: list(error.absolute_path),
        )
        if errors:
            error = errors[0]
            path = "$" + "".join(
                f"[{part}]" if isinstance(part, int) else f".{part}"
                for part in error.absolute_path
            )
            raise ProtocolValidationError(f"{path}: {error.message}")

        if (
            "protocol_version" in message
            and message["protocol_version"] != self.bundle.version
        ):
            raise ProtocolValidationError("unsupported protocol version")

        if message_type in {
            "tool.execution_request",
            "action.authorized",
            "tool.execution_result",
        }:
            tool = message["tool"]
            try:
                validator = self._tool_validators[tool]
            except KeyError as exc:
                raise ProtocolValidationError(
                    f"unknown canonical tool: {tool}"
                ) from exc
            parameter_errors = list(validator.iter_errors(message["parameters"]))
            if parameter_errors:
                raise ProtocolValidationError(
                    f"$.parameters: {parameter_errors[0].message}"
                )

        if message_type in {"tool.execution_request", "action.authorized"}:
            if (
                message["tool"] in STATE_BOUND_TOOLS
                and message["state_precondition"] is None
            ):
                raise ProtocolValidationError(
                    "state-bound action requires state_precondition"
                )
            if _timestamp(message["requested_at"]) >= _timestamp(message["deadline"]):
                raise ProtocolValidationError(
                    "request deadline must follow requested_at"
                )
            if message["tool"] == "phone.swipe":
                state_id = message["state_precondition"]["state_id"]
                if any(
                    message["parameters"][point]["state_id"] != state_id
                    for point in ("start", "end")
                ):
                    raise ProtocolValidationError(
                        "swipe points must bind to the precondition state"
                    )

        if message_type == "action.authorized":
            if message["action_digest"] != action_digest(message):
                raise ProtocolValidationError(
                    "action_digest does not bind the normalized action"
                )
            issued = _timestamp(message["issued_at"])
            expires = _timestamp(message["expires_at"])
            if not issued < expires <= _timestamp(message["deadline"]):
                raise ProtocolValidationError(
                    "authorization expiry is outside request bounds"
                )
            if (expires - issued).total_seconds() > 30:
                raise ProtocolValidationError(
                    "authorization lifetime exceeds 30 seconds"
                )

        if message_type == "tool.execution_result":
            _timestamp(message["timestamp"])
            for field in ("before_state", "after_state"):
                state = message[field]
                if state is not None:
                    self._validate_phone_state(state)
                    if state["device_id"] != message["device_id"]:
                        raise ProtocolValidationError(
                            "PhoneState device must match execution result device"
                        )
            error = message["error"]
            expected_recoverable = (
                error is not None
                and error["retry_disposition"] in RECOVERABLE_DISPOSITIONS
            )
            if message["recoverable"] is not expected_recoverable:
                raise ProtocolValidationError(
                    "recoverable must derive from retry_disposition"
                )
            if message["execution_status"] == "SUCCEEDED" and error is not None:
                raise ProtocolValidationError(
                    "successful execution cannot contain an error"
                )

        if message_type == "mobile.event":
            _timestamp(message["timestamp"])

    @staticmethod
    def _validate_phone_state(state: dict[str, Any]) -> None:
        status = state["capture_status"]
        errors = state["capture_errors"]
        previous = state["previous_state_id"]
        fingerprint = state["screen_fingerprint"]
        transition = state["transition"]

        if status == "COMPLETE" and errors:
            raise ProtocolValidationError(
                "complete PhoneState cannot contain capture errors"
            )
        if status != "COMPLETE" and not errors:
            raise ProtocolValidationError(
                "incomplete PhoneState requires capture errors"
            )
        if state["state_id"] == previous:
            raise ProtocolValidationError(
                "PhoneState cannot reference itself as predecessor"
            )
        if (
            state["foreground_package"] is None
            and state["foreground_activity"] is not None
        ):
            raise ProtocolValidationError(
                "PhoneState activity requires a foreground package"
            )
        if status == "COMPLETE" and fingerprint is None:
            raise ProtocolValidationError(
                "complete PhoneState requires a screen fingerprint"
            )
        if previous is None and transition != "UNKNOWN":
            raise ProtocolValidationError("first PhoneState transition must be unknown")
        if transition in {"NONE", "CHANGED"} and (
            status != "COMPLETE" or fingerprint is None
        ):
            raise ProtocolValidationError(
                "definite PhoneState transition requires complete comparable state"
            )
