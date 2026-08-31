"""Redacted audit seam for protected Mobile Runtime execution.

HMR-105 defines only the record contract. HMR-107 owns durable append-only
storage, integrity chaining, retention, and user-facing export.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True, slots=True)
class RouteAuditRecord:
    """Safe correlation metadata; never contains raw parameters or UI data."""

    stage: str
    protocol_version: str
    request_id: str
    task_id: str
    span_id: str
    device_id: str
    tool: str
    parameter_digest: str
    permission_decision_id: str | None
    action_digest: str | None = None
    effective_risk: str | None = None
    execution_status: str | None = None
    before_state_id: str | None = None
    after_state_id: str | None = None


class ExecutionAuditSink(Protocol):
    """Protected audit dependency required by every Runtime route."""

    def append(self, record: RouteAuditRecord) -> None:
        """Commit one redacted route record or raise on failure."""
