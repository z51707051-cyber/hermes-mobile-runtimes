"""Protected Mobile Runtime routing primitives.

The package is deliberately independent from Hermes' model-tool registry.
HMR-104 defines the only broker/device routing path; HMR-105 adds the required
redacted Audit seam used by the first Android read-only provider.
"""

from .audit import ExecutionAuditSink, RouteAuditRecord
from .capability_registry import (
    CANONICAL_CAPABILITIES,
    CapabilityDefinition,
    CapabilityRegistry,
    CapabilityReport,
    CapabilityUnavailableError,
    DeviceCapabilitySnapshot,
)
from .router import (
    AuthorizedDeviceTransport,
    PolicyBroker,
    ToolRouteError,
    ToolRouter,
)

__all__ = [
    "CANONICAL_CAPABILITIES",
    "AuthorizedDeviceTransport",
    "CapabilityDefinition",
    "CapabilityRegistry",
    "CapabilityReport",
    "CapabilityUnavailableError",
    "DeviceCapabilitySnapshot",
    "ExecutionAuditSink",
    "PolicyBroker",
    "RouteAuditRecord",
    "ToolRouteError",
    "ToolRouter",
]
