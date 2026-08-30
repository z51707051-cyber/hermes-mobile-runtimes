"""Fail-closed availability registry for Mobile Runtime capabilities."""

from __future__ import annotations

from dataclasses import dataclass
from threading import RLock
from typing import Iterable


@dataclass(frozen=True, slots=True)
class CapabilityDefinition:
    """Runtime-owned facts that an untrusted device report cannot lower."""

    tool: str
    minimum_risk: str
    state_changing: bool


CANONICAL_CAPABILITIES = (
    CapabilityDefinition("phone.read_screen", "L0", False),
    CapabilityDefinition("phone.screenshot", "L0", False),
    CapabilityDefinition("phone.tap", "L1", True),
    CapabilityDefinition("phone.long_press", "L1", True),
    CapabilityDefinition("phone.type", "L2", True),
    CapabilityDefinition("phone.swipe", "L1", True),
    CapabilityDefinition("phone.back", "L1", True),
    CapabilityDefinition("phone.home", "L1", True),
    CapabilityDefinition("phone.open_app", "L1", True),
    CapabilityDefinition("phone.wait", "L0", False),
    CapabilityDefinition("phone.notifications", "L0", False),
    CapabilityDefinition("phone.current_app", "L0", False),
    CapabilityDefinition("phone.device_state", "L0", False),
)
_DEFINITIONS = {definition.tool: definition for definition in CANONICAL_CAPABILITIES}


class CapabilityRegistryError(ValueError):
    """Raised when an availability snapshot violates the closed catalog."""


class CapabilityUnavailableError(LookupError):
    code = "CAPABILITY_UNAVAILABLE"

    def __init__(self, tool: str) -> None:
        super().__init__(f"capability is unavailable: {tool}")
        self.tool = tool


@dataclass(frozen=True, slots=True)
class CapabilityReport:
    """Safe projection of an authenticated device capability report."""

    tool: str
    available: bool
    provider_id: str
    reason_code: str | None = None

    def __post_init__(self) -> None:
        if self.tool not in _DEFINITIONS:
            raise CapabilityRegistryError(f"unknown canonical capability: {self.tool}")
        if not 1 <= len(self.provider_id) <= 128:
            raise CapabilityRegistryError("provider_id must contain 1..128 characters")
        if self.reason_code is not None and not 1 <= len(self.reason_code) <= 64:
            raise CapabilityRegistryError("reason_code must contain 1..64 characters")
        if self.available and self.reason_code is not None:
            raise CapabilityRegistryError(
                "available capability cannot contain a reason_code"
            )


@dataclass(frozen=True, slots=True)
class DeviceCapabilitySnapshot:
    """One monotonic, immutable availability view for an enrolled device."""

    device_id: str
    generation: int
    reports: tuple[CapabilityReport, ...]

    def report_for(self, tool: str) -> CapabilityReport | None:
        return next((report for report in self.reports if report.tool == tool), None)


class CapabilityRegistry:
    """Stores availability without mutating the conversation's Tool schemas."""

    def __init__(self) -> None:
        self._snapshots: dict[str, DeviceCapabilitySnapshot] = {}
        self._lock = RLock()

    @property
    def definitions(self) -> tuple[CapabilityDefinition, ...]:
        return CANONICAL_CAPABILITIES

    def replace_snapshot(
        self,
        *,
        device_id: str,
        generation: int,
        reports: Iterable[CapabilityReport],
    ) -> DeviceCapabilitySnapshot:
        if not 1 <= len(device_id) <= 128:
            raise CapabilityRegistryError("device_id must contain 1..128 characters")
        if generation < 1:
            raise CapabilityRegistryError("capability generation must be positive")
        normalized = tuple(sorted(reports, key=lambda report: report.tool))
        if len({report.tool for report in normalized}) != len(normalized):
            raise CapabilityRegistryError("capability report contains duplicate tools")

        snapshot = DeviceCapabilitySnapshot(device_id, generation, normalized)
        with self._lock:
            previous = self._snapshots.get(device_id)
            if previous is not None and generation <= previous.generation:
                raise CapabilityRegistryError(
                    "capability generation must increase monotonically"
                )
            self._snapshots[device_id] = snapshot
        return snapshot

    def snapshot(self, device_id: str) -> DeviceCapabilitySnapshot | None:
        with self._lock:
            return self._snapshots.get(device_id)

    def require_available(
        self, device_id: str, tool: str
    ) -> tuple[CapabilityDefinition, CapabilityReport]:
        try:
            definition = _DEFINITIONS[tool]
        except KeyError as exc:
            raise CapabilityRegistryError(
                f"unknown canonical capability: {tool}"
            ) from exc
        snapshot = self.snapshot(device_id)
        report = snapshot.report_for(tool) if snapshot is not None else None
        if report is None or not report.available:
            raise CapabilityUnavailableError(tool)
        return definition, report
