"""Protected Mobile Runtime routing primitives.

The package is deliberately independent from Hermes' model-tool registry.
HMR-104 defines the only broker/device routing path but registers no real
Android capability.
"""

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
    "PolicyBroker",
    "ToolRouteError",
    "ToolRouter",
]
