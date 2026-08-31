"""The sole Runtime route from planner request to authorized device action."""

from __future__ import annotations

from typing import Protocol

from hermes_mobile.protocol import (
    ProtocolCodec,
    ProtocolValidationError,
    sha256_digest,
)

from .capability_registry import (
    CapabilityRegistry,
    CapabilityRegistryError,
    CapabilityUnavailableError,
)
from .audit import ExecutionAuditSink, RouteAuditRecord


_REQUEST_BINDING_FIELDS = frozenset({
    "protocol_version",
    "request_id",
    "task_id",
    "span_id",
    "device_id",
    "tool",
    "parameters",
    "state_precondition",
    "verification",
    "idempotency_key",
    "attempt",
    "requested_at",
    "deadline",
    "extensions",
})
_RESULT_BINDING_FIELDS = frozenset({
    "protocol_version",
    "request_id",
    "task_id",
    "span_id",
    "device_id",
    "tool",
    "parameters",
    "attempt",
    "idempotency_key",
})
_RISK_ORDER = {f"L{level}": level for level in range(6)}
_POLICY_TERMINAL_STATUSES = frozenset({
    "NOT_STARTED",
    "AWAITING_CONFIRMATION",
    "FAILED",
    "DENIED",
    "CANCELLED",
    "TIMED_OUT",
})


class PolicyBroker(Protocol):
    """Protected-process client; never exposes a signer to Hermes."""

    def evaluate(self, request: bytes) -> bytes:
        """Return an AuthorizedAction or terminal ToolExecutionResult."""


class AuthorizedDeviceTransport(Protocol):
    """Narrow transport that accepts only a broker-issued AuthorizedAction."""

    def execute(self, authorized_action: bytes) -> bytes:
        """Return a ToolExecutionResult from the Android Tool Router."""


class ToolRouteError(RuntimeError):
    """Typed, safe routing failure that never authorizes a retry."""

    def __init__(self, code: str, owner: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.owner = owner


class ToolRouter:
    """Validate → availability → Gate → authorized device dispatch.

    The public route accepts only a serialized ToolExecutionRequest. Callers
    cannot submit an AuthorizedAction, select a provider, or obtain the opaque
    execution authorization returned by the protected broker.
    """

    def __init__(
        self,
        *,
        capabilities: CapabilityRegistry,
        policy_broker: PolicyBroker,
        device_transport: AuthorizedDeviceTransport,
        audit_sink: ExecutionAuditSink,
        codec: ProtocolCodec | None = None,
    ) -> None:
        self._capabilities = capabilities
        self._policy_broker = policy_broker
        self._device_transport = device_transport
        self._audit_sink = audit_sink
        self._codec = codec or ProtocolCodec()

    def route(self, payload: bytes | str) -> bytes:
        request = self._decode(payload, owner="RUNTIME")
        if request["message_type"] != "tool.execution_request":
            raise ToolRouteError(
                "ACTION_REJECTED",
                "RUNTIME",
                "Tool Router accepts only ToolExecutionRequest",
            )

        try:
            definition, _ = self._capabilities.require_available(
                request["device_id"], request["tool"]
            )
        except CapabilityUnavailableError as exc:
            raise ToolRouteError(exc.code, "RUNTIME", str(exc)) from exc
        except CapabilityRegistryError as exc:
            raise ToolRouteError(
                "ACTION_REJECTED", "RUNTIME", "invalid capability lookup"
            ) from exc

        request_bytes = self._codec.encode(request)
        decision_bytes = self._call_policy(request_bytes)
        decision = self._decode(decision_bytes, owner="POLICY")

        if decision["message_type"] == "tool.execution_result":
            self._validate_result_binding(request, decision, decision_id=None)
            if decision["execution_status"] not in _POLICY_TERMINAL_STATUSES:
                raise ToolRouteError(
                    "ACTION_REJECTED",
                    "POLICY",
                    "policy broker returned an executable result",
                )
            if decision["permission_decision_id"] is None:
                raise ToolRouteError(
                    "ACTION_REJECTED",
                    "POLICY",
                    "policy result is missing its decision identifier",
                )
            self._audit_result(request, decision)
            return self._codec.encode(decision)

        if decision["message_type"] != "action.authorized":
            raise ToolRouteError(
                "ACTION_REJECTED", "POLICY", "policy broker returned wrong message type"
            )
        self._validate_action_binding(request, decision)
        if (
            _RISK_ORDER[decision["effective_risk"]]
            < _RISK_ORDER[definition.minimum_risk]
        ):
            raise ToolRouteError(
                "ACTION_REJECTED",
                "POLICY",
                "effective risk is below the capability baseline",
            )

        # Availability is rechecked immediately before leaving the protected
        # broker path. Android performs its own independent provider check.
        try:
            self._capabilities.require_available(request["device_id"], request["tool"])
        except CapabilityUnavailableError as exc:
            raise ToolRouteError(exc.code, "RUNTIME", str(exc)) from exc

        self._audit_authorization(request, decision)
        action_bytes = self._codec.encode(decision)
        result_bytes = self._call_device(action_bytes)
        result = self._decode(result_bytes, owner="ANDROID_PEP")
        if result["message_type"] != "tool.execution_result":
            raise ToolRouteError(
                "ACTION_REJECTED",
                "ANDROID_PEP",
                "Android Router returned wrong message type",
            )
        self._validate_result_binding(
            request, result, decision_id=decision["policy_decision_id"]
        )
        self._audit_result(request, result)
        return self._codec.encode(result)

    def _audit_authorization(self, request: dict, action: dict) -> None:
        self._append_audit(
            RouteAuditRecord(
                stage="AUTHORIZED",
                protocol_version=request["protocol_version"],
                request_id=request["request_id"],
                task_id=request["task_id"],
                span_id=request["span_id"],
                device_id=request["device_id"],
                tool=request["tool"],
                parameter_digest=sha256_digest(request["parameters"]),
                permission_decision_id=action["policy_decision_id"],
                action_digest=action["action_digest"],
                effective_risk=action["effective_risk"],
            )
        )

    def _audit_result(self, request: dict, result: dict) -> None:
        self._append_audit(
            RouteAuditRecord(
                stage="RESULT",
                protocol_version=request["protocol_version"],
                request_id=request["request_id"],
                task_id=request["task_id"],
                span_id=request["span_id"],
                device_id=request["device_id"],
                tool=request["tool"],
                parameter_digest=result["parameter_digest"],
                permission_decision_id=result["permission_decision_id"],
                execution_status=result["execution_status"],
                before_state_id=self._state_id(result["before_state"]),
                after_state_id=self._state_id(result["after_state"]),
            )
        )

    def _append_audit(self, record: RouteAuditRecord) -> None:
        try:
            self._audit_sink.append(record)
        except Exception as exc:
            raise ToolRouteError(
                "AUDIT_UNAVAILABLE",
                "RUNTIME",
                "protected execution audit is unavailable",
            ) from exc

    @staticmethod
    def _state_id(state: dict | None) -> str | None:
        return state["state_id"] if state is not None else None

    def _decode(self, payload: bytes | str, *, owner: str) -> dict:
        try:
            return self._codec.decode(payload)
        except ProtocolValidationError as exc:
            raise ToolRouteError(
                "PROTOCOL_INCOMPATIBLE", owner, "invalid protocol message"
            ) from exc

    def _call_policy(self, request: bytes) -> bytes:
        try:
            response = self._policy_broker.evaluate(request)
        except Exception as exc:
            raise ToolRouteError(
                "TRANSPORT_UNAVAILABLE", "POLICY", "policy broker is unavailable"
            ) from exc
        if not isinstance(response, bytes):
            raise ToolRouteError(
                "ACTION_REJECTED", "POLICY", "policy broker returned non-bytes payload"
            )
        return response

    def _call_device(self, action: bytes) -> bytes:
        try:
            response = self._device_transport.execute(action)
        except Exception as exc:
            raise ToolRouteError(
                "TRANSPORT_UNAVAILABLE", "TRANSPORT", "Android device is unavailable"
            ) from exc
        if not isinstance(response, bytes):
            raise ToolRouteError(
                "ACTION_REJECTED",
                "ANDROID_PEP",
                "Android Router returned non-bytes payload",
            )
        return response

    @staticmethod
    def _validate_action_binding(request: dict, action: dict) -> None:
        for field in _REQUEST_BINDING_FIELDS:
            if request.get(field) != action.get(field):
                raise ToolRouteError(
                    "ACTION_REJECTED",
                    "POLICY",
                    f"authorized action changed request field: {field}",
                )

    @staticmethod
    def _validate_result_binding(
        request: dict, result: dict, *, decision_id: str | None
    ) -> None:
        owner = "ANDROID_PEP" if decision_id is not None else "POLICY"
        for field in _RESULT_BINDING_FIELDS:
            if request[field] != result[field]:
                raise ToolRouteError(
                    "ACTION_REJECTED",
                    owner,
                    f"execution result changed request field: {field}",
                )
        expected_digest = sha256_digest(request["parameters"])
        if result["parameter_digest"] != expected_digest:
            raise ToolRouteError("ACTION_REJECTED", owner, "parameter digest mismatch")
        if decision_id is not None and result["permission_decision_id"] != decision_id:
            raise ToolRouteError(
                "ACTION_REJECTED", "ANDROID_PEP", "permission decision mismatch"
            )
