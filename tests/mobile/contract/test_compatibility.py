from __future__ import annotations

import copy

import pytest

from hermes_mobile.protocol import CompatibilityError, CompatibilityOffer, negotiate


def test_identical_peers_negotiate_highest_patch(load_fixture) -> None:
    message = load_fixture("valid/compatibility-offer.json")
    message["protocol_max"] = "0.1.3"
    peer = copy.deepcopy(message)
    peer["protocol_min"] = "0.1.1"
    peer["features"].append("optional-observation")

    selection = negotiate(
        CompatibilityOffer.from_message(message),
        CompatibilityOffer.from_message(peer),
    )

    assert str(selection.selected_version) == "0.1.3"
    assert selection.accepted_features == ("closed-schema", "strict-json")


@pytest.mark.parametrize("field", ["schema_bundle_digest", "tool_schema_digests"])
def test_digest_mismatch_fails_closed(load_fixture, field: str) -> None:
    local = load_fixture("valid/compatibility-offer.json")
    remote = copy.deepcopy(local)
    if field == "schema_bundle_digest":
        remote[field] = "sha256:" + "0" * 64
    else:
        remote[field]["phone.tap"] = "sha256:" + "0" * 64

    with pytest.raises(CompatibilityError, match="digest mismatch"):
        negotiate(
            CompatibilityOffer.from_message(local),
            CompatibilityOffer.from_message(remote),
        )


def test_version_downgrade_and_missing_feature_fail_closed(load_fixture) -> None:
    local = load_fixture("valid/compatibility-offer.json")
    remote = copy.deepcopy(local)
    remote["protocol_min"] = "0.0.9"
    remote["protocol_max"] = "0.0.9"
    with pytest.raises(CompatibilityError, match="no acceptable"):
        negotiate(
            CompatibilityOffer.from_message(local),
            CompatibilityOffer.from_message(remote),
        )

    remote = copy.deepcopy(local)
    remote["features"] = ["strict-json"]
    remote["required_features"] = []
    with pytest.raises(CompatibilityError, match="required"):
        negotiate(
            CompatibilityOffer.from_message(local),
            CompatibilityOffer.from_message(remote),
        )
