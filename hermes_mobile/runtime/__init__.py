"""Protected Mobile Runtime routing primitives.

The package is deliberately independent from Hermes' model-tool registry.
HMR-104 defines the only broker/device routing path; HMR-107 provides its
durable, redacted, integrity-chained Audit implementation.
"""

from .audit import (
    AUDIT_ENCRYPTION_ALGORITHM,
    AUDIT_REDACTION_PROFILE,
    Aes256GcmAuditCipher,
    AuditAuthenticator,
    AuditCipher,
    AuditConflictError,
    AuditEnvelope,
    AuditError,
    AuditIntegrityError,
    AuditRecordError,
    AuditStoreUnavailableError,
    AuditVerificationResult,
    ExecutionAuditSink,
    HmacSha256AuditAuthenticator,
    RouteAuditRecord,
    SQLiteAuditStore,
)
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
    "AUDIT_ENCRYPTION_ALGORITHM",
    "AUDIT_REDACTION_PROFILE",
    "CANONICAL_CAPABILITIES",
    "Aes256GcmAuditCipher",
    "AuditAuthenticator",
    "AuditCipher",
    "AuditConflictError",
    "AuditEnvelope",
    "AuditError",
    "AuditIntegrityError",
    "AuditRecordError",
    "AuditStoreUnavailableError",
    "AuditVerificationResult",
    "AuthorizedDeviceTransport",
    "CapabilityDefinition",
    "CapabilityRegistry",
    "CapabilityReport",
    "CapabilityUnavailableError",
    "DeviceCapabilitySnapshot",
    "ExecutionAuditSink",
    "HmacSha256AuditAuthenticator",
    "PolicyBroker",
    "RouteAuditRecord",
    "SQLiteAuditStore",
    "ToolRouteError",
    "ToolRouter",
]
