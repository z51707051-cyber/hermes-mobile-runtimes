from __future__ import annotations

import pytest

from hermes_mobile.protocol import canonical_json, sha256_digest
from hermes_mobile.protocol.strict_json import StrictJsonError, dumps, loads


def test_duplicate_keys_and_non_finite_numbers_fail_closed() -> None:
    with pytest.raises(StrictJsonError, match="duplicate"):
        loads(b'{"action":"tap","action":"home"}')
    with pytest.raises(StrictJsonError, match="non-finite"):
        loads(b'{"value":NaN}')


def test_invalid_utf8_and_unsafe_integers_are_rejected() -> None:
    with pytest.raises(StrictJsonError, match="UTF-8"):
        loads(b'"\xff"')
    with pytest.raises(StrictJsonError, match="safe range"):
        loads(b"9007199254740992")


def test_deterministic_json_round_trip() -> None:
    value = {"z": [True, None], "a": {"number": 7}}

    assert dumps(value) == b'{"a":{"number":7},"z":[true,null]}'
    assert loads(dumps(value)) == value


def test_canonical_action_digest_matches_shared_fixture(load_fixture) -> None:
    fixture = load_fixture("canonical/action-digest.json")

    assert canonical_json(fixture["input"]).decode() == fixture["canonical"]
    assert sha256_digest(fixture["input"]) == fixture["digest"]


def test_security_digest_rejects_floating_point() -> None:
    with pytest.raises(StrictJsonError, match="floating-point"):
        canonical_json({"risk": 1.0})
