"""Mobile Agent Protocol V0.1 codec and compatibility contract."""

from .canonical import (
    action_digest,
    action_digest_material,
    canonical_json,
    sha256_digest,
)
from .codec import ProtocolCodec, ProtocolValidationError
from .compatibility import (
    CompatibilityError,
    CompatibilityOffer,
    CompatibilitySelection,
    negotiate,
)
from .schema_bundle import SchemaBundle, SchemaBundleError

__all__ = [
    "CompatibilityError",
    "CompatibilityOffer",
    "CompatibilitySelection",
    "ProtocolCodec",
    "ProtocolValidationError",
    "SchemaBundle",
    "SchemaBundleError",
    "action_digest",
    "action_digest_material",
    "canonical_json",
    "negotiate",
    "sha256_digest",
]
