"""Fail-closed Mobile Agent Protocol compatibility negotiation."""

from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Any


_VERSION = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")


class CompatibilityError(ValueError):
    code = "PROTOCOL_INCOMPATIBLE"


@dataclass(frozen=True, order=True)
class ProtocolVersion:
    major: int
    minor: int
    patch: int

    @classmethod
    def parse(cls, raw: str) -> "ProtocolVersion":
        match = _VERSION.fullmatch(raw)
        if match is None:
            raise CompatibilityError(f"invalid protocol version: {raw}")
        return cls(*(int(part) for part in match.groups()))

    def __str__(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"


@dataclass(frozen=True)
class CompatibilityOffer:
    protocol_min: ProtocolVersion
    protocol_max: ProtocolVersion
    schema_bundle_digest: str
    tool_schema_digests: dict[str, str]
    features: frozenset[str]
    required_features: frozenset[str]

    @classmethod
    def from_message(cls, message: dict[str, Any]) -> "CompatibilityOffer":
        if message.get("message_type") != "compatibility.offer":
            raise CompatibilityError("message is not a compatibility offer")
        minimum = ProtocolVersion.parse(message["protocol_min"])
        maximum = ProtocolVersion.parse(message["protocol_max"])
        if minimum > maximum:
            raise CompatibilityError("protocol range minimum exceeds maximum")
        if (minimum.major, minimum.minor) != (maximum.major, maximum.minor):
            raise CompatibilityError("V0.1 offers may span patch versions only")
        features = frozenset(message["features"])
        required = frozenset(message["required_features"])
        if not required <= features:
            raise CompatibilityError("required features must also be advertised")
        return cls(
            protocol_min=minimum,
            protocol_max=maximum,
            schema_bundle_digest=message["schema_bundle_digest"],
            tool_schema_digests=dict(message["tool_schema_digests"]),
            features=features,
            required_features=required,
        )


@dataclass(frozen=True)
class CompatibilitySelection:
    selected_version: ProtocolVersion
    schema_bundle_digest: str
    accepted_features: tuple[str, ...]

    def to_message(self) -> dict[str, Any]:
        return {
            "message_type": "compatibility.selection",
            "selected_version": str(self.selected_version),
            "schema_bundle_digest": self.schema_bundle_digest,
            "accepted_features": list(self.accepted_features),
        }


def negotiate(
    local: CompatibilityOffer,
    remote: CompatibilityOffer,
    *,
    minimum_accepted: str = "0.1.1",
) -> CompatibilitySelection:
    floor = max(
        local.protocol_min, remote.protocol_min, ProtocolVersion.parse(minimum_accepted)
    )
    ceiling = min(local.protocol_max, remote.protocol_max)
    if floor > ceiling or (floor.major, floor.minor) != (ceiling.major, ceiling.minor):
        raise CompatibilityError("peers have no acceptable protocol version")
    if local.schema_bundle_digest != remote.schema_bundle_digest:
        raise CompatibilityError("schema bundle digest mismatch")
    if local.tool_schema_digests != remote.tool_schema_digests:
        raise CompatibilityError("tool schema digest mismatch")
    if not local.required_features <= remote.features:
        raise CompatibilityError("remote peer lacks a required local feature")
    if not remote.required_features <= local.features:
        raise CompatibilityError("local peer lacks a required remote feature")
    return CompatibilitySelection(
        selected_version=ceiling,
        schema_bundle_digest=local.schema_bundle_digest,
        accepted_features=tuple(sorted(local.features & remote.features)),
    )
