from __future__ import annotations

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sqlite3
import stat

import pytest

from hermes_mobile.protocol import sha256_digest
from hermes_mobile.runtime import (
    Aes256GcmAuditCipher,
    AuditConflictError,
    AuditIntegrityError,
    AuditRecordError,
    AuditStoreUnavailableError,
    HmacSha256AuditAuthenticator,
    RouteAuditRecord,
    SQLiteAuditStore,
)


NOW = datetime(2026, 9, 8, 12, 0, tzinfo=timezone.utc)
KEY_1 = b"audit-test-key-1".ljust(32, b"!")
KEY_2 = b"audit-test-key-2".ljust(32, b"!")
ENCRYPTION_KEY_1 = b"encryption-test-key-1".ljust(32, b"!")
ENCRYPTION_KEY_2 = b"encryption-test-key-2".ljust(32, b"!")


def _authenticator(
    *,
    active: str = "audit-key-1",
    include_old: bool = True,
) -> HmacSha256AuditAuthenticator:
    keys = {"audit-key-2": KEY_2}
    if include_old:
        keys["audit-key-1"] = KEY_1
    if active == "audit-key-1":
        keys = {"audit-key-1": KEY_1}
    return HmacSha256AuditAuthenticator(keys, active_key_id=active)


def _authorized(
    *,
    request_id: str = "req-0001",
    task_id: str = "task-0001",
    attempt: int = 1,
    parameter_digest: str | None = None,
) -> RouteAuditRecord:
    return RouteAuditRecord(
        stage="AUTHORIZED",
        protocol_version="0.1.1",
        request_id=request_id,
        task_id=task_id,
        span_id=f"span-{request_id}",
        device_id="device-0001",
        tool="phone.open_app",
        attempt=attempt,
        parameter_digest=parameter_digest
        or sha256_digest({"package": "com.private.application"}),
        permission_decision_id=f"decision-{request_id}",
        action_digest="sha256:" + "a" * 64,
        effective_risk="L1",
    )


def _result(
    *,
    request_id: str = "req-0001",
    task_id: str = "task-0001",
    attempt: int = 1,
    status: str = "SUCCEEDED",
) -> RouteAuditRecord:
    return RouteAuditRecord(
        stage="RESULT",
        protocol_version="0.1.1",
        request_id=request_id,
        task_id=task_id,
        span_id=f"span-{request_id}",
        device_id="device-0001",
        tool="phone.open_app",
        attempt=attempt,
        parameter_digest=sha256_digest({"package": "com.private.application"}),
        permission_decision_id=f"decision-{request_id}",
        execution_status=status,
        outcome_code=None if status == "SUCCEEDED" else "ACTION_REJECTED",
        before_state_id="state-before",
        after_state_id="state-after",
    )


def _store(path: Path, *, active: str = "audit-key-1") -> SQLiteAuditStore:
    encryption_keys = {"encryption-key-1": ENCRYPTION_KEY_1}
    encryption_active = "encryption-key-1"
    if active == "audit-key-2":
        encryption_keys["encryption-key-2"] = ENCRYPTION_KEY_2
        encryption_active = "encryption-key-2"
    return SQLiteAuditStore(
        path,
        authenticator=_authenticator(active=active),
        cipher=Aes256GcmAuditCipher(
            encryption_keys,
            active_key_id=encryption_active,
        ),
        clock=lambda: NOW,
    )


def test_append_reopen_verify_and_export_redacted_task(tmp_path: Path) -> None:
    database = tmp_path / "protected" / "audit.sqlite3"
    store = _store(database)
    store.append(_authorized())
    store.append(_result())

    verification = store.verify()
    records = store.records_for_task("task-0001")
    exported = store.export_task("task-0001", tmp_path / "audit")
    store.close()

    assert verification.record_count == 2
    assert verification.last_digest == records[-1].record_digest
    assert records[0].sequence == 1
    assert records[0].previous_digest is None
    assert records[1].previous_digest == records[0].record_digest
    assert exported.name == "task-0001.json"
    document = json.loads(exported.read_bytes())
    assert document["integrity_status"] == "VERIFIED"
    assert [entry["record"]["stage"] for entry in document["records"]] == [
        "AUTHORIZED",
        "RESULT",
    ]
    assert b"com.private.application" not in database.read_bytes()
    assert b"task-0001" not in database.read_bytes()
    assert b"req-0001" not in database.read_bytes()
    assert b"com.private.application" not in exported.read_bytes()
    if os.name != "nt":
        assert stat.S_IMODE(database.stat().st_mode) == 0o600

    reopened = _store(database)
    assert reopened.verify() == verification
    reopened.close()


def test_exact_replay_is_idempotent_but_conflicting_reuse_fails(
    tmp_path: Path,
) -> None:
    store = _store(tmp_path / "audit.sqlite3")
    original = _authorized()
    store.append(original)
    store.append(original)

    assert store.verify().record_count == 1

    conflicting = _authorized(parameter_digest="sha256:" + "b" * 64)
    with pytest.raises(AuditConflictError):
        store.append(conflicting)
    assert store.verify().record_count == 1
    store.close()


def test_sqlite_guards_and_integrity_chain_detect_tampering(tmp_path: Path) -> None:
    database = tmp_path / "audit.sqlite3"
    store = _store(database)
    store.append(_authorized())

    external = sqlite3.connect(database)
    with pytest.raises(sqlite3.IntegrityError, match="append-only"):
        external.execute(
            "UPDATE audit_records SET ciphertext = x'00' WHERE sequence = 1"
        )
    external.close()
    store.close()

    external = sqlite3.connect(database)
    external.execute("DROP TRIGGER audit_records_no_update")
    ciphertext = external.execute(
        "SELECT ciphertext FROM audit_records WHERE sequence = 1"
    ).fetchone()[0]
    changed = bytes([ciphertext[0] ^ 1]) + ciphertext[1:]
    external.execute(
        "UPDATE audit_records SET ciphertext = ? WHERE sequence = 1",
        (changed,),
    )
    external.commit()
    external.close()

    with pytest.raises(AuditIntegrityError, match="ciphertext"):
        _store(database)


def test_key_rotation_preserves_historical_verification(tmp_path: Path) -> None:
    database = tmp_path / "audit.sqlite3"
    first = _store(database)
    first.append(_authorized())
    first.close()

    rotated = SQLiteAuditStore(
        database,
        authenticator=_authenticator(active="audit-key-2"),
        cipher=Aes256GcmAuditCipher(
            {
                "encryption-key-1": ENCRYPTION_KEY_1,
                "encryption-key-2": ENCRYPTION_KEY_2,
            },
            active_key_id="encryption-key-2",
        ),
        clock=lambda: NOW,
    )
    rotated.append(_result())
    records = rotated.records_for_task("task-0001")
    assert [record.integrity_key_id for record in records] == [
        "audit-key-1",
        "audit-key-2",
    ]
    assert [record.encryption_key_id for record in records] == [
        "encryption-key-1",
        "encryption-key-2",
    ]
    rotated.close()

    with pytest.raises(AuditIntegrityError, match="signature"):
        SQLiteAuditStore(
            database,
            authenticator=_authenticator(
                active="audit-key-2",
                include_old=False,
            ),
            cipher=Aes256GcmAuditCipher(
                {
                    "encryption-key-1": ENCRYPTION_KEY_1,
                    "encryption-key-2": ENCRYPTION_KEY_2,
                },
                active_key_id="encryption-key-2",
            ),
        )


def test_record_shape_rejects_untrusted_or_inconsistent_metadata() -> None:
    with pytest.raises(AuditRecordError, match="stage"):
        RouteAuditRecord(
            **{
                **_authorized().as_dict(),
                "stage": "RAW_PARAMETERS",
            }
        )
    with pytest.raises(AuditRecordError, match="inconsistent"):
        RouteAuditRecord(
            **{
                **_authorized().as_dict(),
                "execution_status": "SUCCEEDED",
            }
        )
    with pytest.raises(ValueError, match="256 bits"):
        HmacSha256AuditAuthenticator(
            {"weak": b"short"},
            active_key_id="weak",
        )
    with pytest.raises(ValueError, match="256-bit"):
        Aes256GcmAuditCipher(
            {"weak": b"short"},
            active_key_id="weak",
        )


def test_invalid_nonce_rolls_back_without_partial_record(tmp_path: Path) -> None:
    database = tmp_path / "audit.sqlite3"
    store = SQLiteAuditStore(
        database,
        authenticator=_authenticator(),
        cipher=Aes256GcmAuditCipher(
            {"encryption-key-1": ENCRYPTION_KEY_1},
            active_key_id="encryption-key-1",
        ),
        clock=lambda: NOW,
        nonce_source=lambda _: b"too-short",
    )

    with pytest.raises(AuditIntegrityError, match="nonce"):
        store.append(_authorized())

    assert store.verify().record_count == 0
    store.close()


def test_closed_store_fails_instead_of_silently_dropping_audit(
    tmp_path: Path,
) -> None:
    store = _store(tmp_path / "audit.sqlite3")
    store.close()

    with pytest.raises(AuditStoreUnavailableError, match="closed"):
        store.append(_authorized())
