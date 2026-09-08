"""Durable, redacted audit storage for protected Mobile Runtime execution."""

from __future__ import annotations

import base64
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import hmac
import os
from pathlib import Path
import re
import sqlite3
import stat
import tempfile
import threading
from typing import Any, Protocol

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from hermes_mobile.protocol.canonical import canonical_json, sha256_digest
from hermes_mobile.protocol.strict_json import StrictJsonError, loads


AUDIT_SCHEMA_VERSION = 1
AUDIT_INTEGRITY_ALGORITHM = "HMAC-SHA256"
AUDIT_ENCRYPTION_ALGORITHM = "AES-256-GCM"
AUDIT_REDACTION_PROFILE = "route-metadata-v1"
_OPAQUE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
_TOOL = re.compile(r"phone\.[a-z][a-z0-9_]{0,63}")
_PROTOCOL_VERSION = re.compile(r"0\.1\.[0-9]+")
_DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
_ERROR_CODE = re.compile(r"[A-Z][A-Z0-9_]{0,63}")
_STAGES = frozenset({"AUTHORIZED", "RESULT"})
_RISKS = frozenset({f"L{level}" for level in range(6)})
_EXECUTION_STATUSES = frozenset(
    {
        "NOT_STARTED",
        "AWAITING_CONFIRMATION",
        "SUCCEEDED",
        "FAILED",
        "DENIED",
        "CANCELLED",
        "TIMED_OUT",
        "UNKNOWN_OUTCOME",
    }
)
_RECORD_FIELDS = frozenset(
    {
        "stage",
        "protocol_version",
        "request_id",
        "task_id",
        "span_id",
        "device_id",
        "tool",
        "attempt",
        "parameter_digest",
        "permission_decision_id",
        "action_digest",
        "effective_risk",
        "execution_status",
        "outcome_code",
        "precondition_state_id",
        "before_state_id",
        "after_state_id",
        "redaction_profile",
    }
)


class AuditError(RuntimeError):
    """Base class for safe audit failures."""


class AuditRecordError(AuditError):
    """The caller attempted to append an invalid or non-redacted record."""


class AuditConflictError(AuditError):
    """One audit event key was reused with different content."""


class AuditIntegrityError(AuditError):
    """The durable ledger failed chain or authenticator verification."""


class AuditStoreUnavailableError(AuditError):
    """The durable ledger could not complete an atomic operation."""


@dataclass(frozen=True, slots=True)
class RouteAuditRecord:
    """Closed, safe correlation metadata; raw parameters/UI data cannot fit."""

    stage: str
    protocol_version: str
    request_id: str
    task_id: str
    span_id: str
    device_id: str
    tool: str
    attempt: int
    parameter_digest: str
    permission_decision_id: str | None
    action_digest: str | None = None
    effective_risk: str | None = None
    execution_status: str | None = None
    outcome_code: str | None = None
    precondition_state_id: str | None = None
    before_state_id: str | None = None
    after_state_id: str | None = None
    redaction_profile: str = AUDIT_REDACTION_PROFILE

    def __post_init__(self) -> None:
        _validate_record(self)

    def as_dict(self) -> dict[str, Any]:
        return {field: getattr(self, field) for field in sorted(_RECORD_FIELDS)}

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any]) -> RouteAuditRecord:
        if set(value) != _RECORD_FIELDS:
            raise AuditIntegrityError("audit record shape is invalid")
        try:
            return cls(**value)
        except (AuditRecordError, TypeError) as exc:
            raise AuditIntegrityError("audit record content is invalid") from exc


class ExecutionAuditSink(Protocol):
    """Protected audit dependency required by every Runtime route."""

    def append(self, record: RouteAuditRecord) -> None:
        """Atomically commit one redacted route record or raise."""


class AuditAuthenticator(Protocol):
    """Key-bound integrity operations owned by the trusted audit writer."""

    @property
    def active_key_id(self) -> str:
        """Return the opaque key id used for new records."""

    def sign(self, key_id: str, payload: bytes) -> bytes:
        """Authenticate payload with the active key."""

    def verify(self, key_id: str, payload: bytes, signature: bytes) -> bool:
        """Verify a historical record, including after key rotation."""


class AuditCipher(Protocol):
    """Encryption operations owned independently by the trusted audit writer."""

    @property
    def active_key_id(self) -> str:
        """Return the opaque encryption key id used for new records."""

    def encrypt(
        self,
        key_id: str,
        nonce: bytes,
        plaintext: bytes,
        associated_data: bytes,
    ) -> bytes:
        """Encrypt and authenticate one closed audit payload."""

    def decrypt(
        self,
        key_id: str,
        nonce: bytes,
        ciphertext: bytes,
        associated_data: bytes,
    ) -> bytes:
        """Decrypt one historical payload or raise an integrity error."""


class HmacSha256AuditAuthenticator:
    """Small protected-writer keyring; keys never enter records or exports."""

    def __init__(self, keys: Mapping[str, bytes], *, active_key_id: str) -> None:
        if not _OPAQUE_ID.fullmatch(active_key_id):
            raise ValueError("active audit key id is invalid")
        copied = dict(keys)
        if active_key_id not in copied:
            raise ValueError("active audit key is unavailable")
        if not copied or any(
            not _OPAQUE_ID.fullmatch(key_id)
            or not isinstance(key, bytes)
            or len(key) < 32
            for key_id, key in copied.items()
        ):
            raise ValueError("audit keys require opaque ids and at least 256 bits")
        self._keys = copied
        self._active_key_id = active_key_id

    @property
    def active_key_id(self) -> str:
        return self._active_key_id

    def sign(self, key_id: str, payload: bytes) -> bytes:
        if key_id != self._active_key_id:
            raise AuditIntegrityError("audit signer refused a non-active key")
        return hmac.digest(self._keys[key_id], payload, "sha256")

    def verify(self, key_id: str, payload: bytes, signature: bytes) -> bool:
        key = self._keys.get(key_id)
        if key is None:
            return False
        expected = hmac.digest(key, payload, "sha256")
        return hmac.compare_digest(expected, signature)


class Aes256GcmAuditCipher:
    """Protected-writer AES keyring with historical decryption support."""

    def __init__(self, keys: Mapping[str, bytes], *, active_key_id: str) -> None:
        if not _OPAQUE_ID.fullmatch(active_key_id):
            raise ValueError("active audit encryption key id is invalid")
        copied = dict(keys)
        if active_key_id not in copied:
            raise ValueError("active audit encryption key is unavailable")
        if not copied or any(
            not _OPAQUE_ID.fullmatch(key_id)
            or not isinstance(key, bytes)
            or len(key) != 32
            for key_id, key in copied.items()
        ):
            raise ValueError("audit encryption requires opaque ids and 256-bit keys")
        self._keys = copied
        self._active_key_id = active_key_id

    @property
    def active_key_id(self) -> str:
        return self._active_key_id

    def encrypt(
        self,
        key_id: str,
        nonce: bytes,
        plaintext: bytes,
        associated_data: bytes,
    ) -> bytes:
        if key_id != self._active_key_id:
            raise AuditIntegrityError("audit cipher refused a non-active key")
        if len(nonce) != 12:
            raise AuditIntegrityError("audit cipher nonce is invalid")
        return AESGCM(self._keys[key_id]).encrypt(nonce, plaintext, associated_data)

    def decrypt(
        self,
        key_id: str,
        nonce: bytes,
        ciphertext: bytes,
        associated_data: bytes,
    ) -> bytes:
        key = self._keys.get(key_id)
        if key is None or len(nonce) != 12:
            raise AuditIntegrityError("audit encryption key or nonce is unavailable")
        try:
            return AESGCM(key).decrypt(nonce, ciphertext, associated_data)
        except InvalidTag as exc:
            raise AuditIntegrityError("audit ciphertext authentication failed") from exc


@dataclass(frozen=True, slots=True)
class AuditEnvelope:
    sequence: int
    recorded_at: str
    previous_digest: str | None
    record_digest: str
    integrity_algorithm: str
    integrity_key_id: str
    encryption_algorithm: str
    encryption_key_id: str
    nonce: str
    ciphertext_digest: str
    signature: str
    record: RouteAuditRecord

    def as_dict(self) -> dict[str, Any]:
        return {
            "schema_version": AUDIT_SCHEMA_VERSION,
            "sequence": self.sequence,
            "recorded_at": self.recorded_at,
            "previous_digest": self.previous_digest,
            "record_digest": self.record_digest,
            "integrity_algorithm": self.integrity_algorithm,
            "integrity_key_id": self.integrity_key_id,
            "encryption_algorithm": self.encryption_algorithm,
            "encryption_key_id": self.encryption_key_id,
            "nonce": self.nonce,
            "ciphertext_digest": self.ciphertext_digest,
            "signature": self.signature,
            "record": self.record.as_dict(),
        }


@dataclass(frozen=True, slots=True)
class AuditVerificationResult:
    record_count: int
    last_digest: str | None


class SQLiteAuditStore:
    """Crash-safe single-writer audit chain backed by SQLite transactions."""

    def __init__(
        self,
        path: Path,
        *,
        authenticator: AuditAuthenticator,
        cipher: AuditCipher,
        clock: Callable[[], datetime] | None = None,
        nonce_source: Callable[[int], bytes] | None = None,
    ) -> None:
        self._path = Path(path)
        self._authenticator = authenticator
        self._cipher = cipher
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._nonce_source = nonce_source or os.urandom
        self._lock = threading.RLock()
        self._closed = False
        self._prepare_path()
        try:
            self._connection = sqlite3.connect(
                self._path,
                isolation_level=None,
                check_same_thread=False,
            )
            self._connection.row_factory = sqlite3.Row
            self._configure()
            self._initialize_schema()
            self.verify()
        except AuditError:
            self._close_after_failed_open()
            raise
        except (OSError, sqlite3.Error) as exc:
            self._close_after_failed_open()
            raise AuditStoreUnavailableError(
                "audit store initialization failed"
            ) from exc

    def __enter__(self) -> SQLiteAuditStore:
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    @property
    def path(self) -> Path:
        return self._path

    def append(self, record: RouteAuditRecord) -> None:
        payload = canonical_json(record.as_dict())
        with self._lock:
            self._require_open()
            try:
                self._connection.execute("BEGIN IMMEDIATE")
                verification, envelopes = self._verify_locked()
                existing = next(
                    (
                        envelope
                        for envelope in envelopes
                        if _event_identity(envelope.record) == _event_identity(record)
                    ),
                    None,
                )
                if existing is not None:
                    if existing.record != record:
                        raise AuditConflictError(
                            "audit event key was reused with different content"
                        )
                    self._connection.commit()
                    return

                sequence = verification.record_count + 1
                recorded_at = _utc_timestamp(self._clock())
                material = {
                    "schema_version": AUDIT_SCHEMA_VERSION,
                    "sequence": sequence,
                    "recorded_at": recorded_at,
                    "previous_digest": verification.last_digest,
                    "record": record.as_dict(),
                }
                encryption_key_id = self._cipher.active_key_id
                nonce = self._nonce_source(12)
                if not isinstance(nonce, bytes) or len(nonce) != 12:
                    raise AuditIntegrityError(
                        "audit nonce source returned invalid data"
                    )
                associated_data = _encryption_context(
                    sequence=sequence,
                    recorded_at=recorded_at,
                    previous_digest=verification.last_digest,
                    key_id=encryption_key_id,
                )
                ciphertext = self._cipher.encrypt(
                    encryption_key_id,
                    nonce,
                    payload,
                    associated_data,
                )
                ciphertext_digest = _bytes_digest(ciphertext)
                material = {
                    key: value for key, value in material.items() if key != "record"
                } | {
                    "encryption_algorithm": AUDIT_ENCRYPTION_ALGORITHM,
                    "encryption_key_id": encryption_key_id,
                    "nonce": _encode_bytes(nonce),
                    "ciphertext_digest": ciphertext_digest,
                }
                record_digest = sha256_digest(material)
                key_id = self._authenticator.active_key_id
                signature = _encode_signature(
                    self._authenticator.sign(
                        key_id,
                        _signature_material(record_digest, key_id),
                    )
                )
                self._connection.execute(
                    """
                    INSERT INTO audit_records (
                        sequence, recorded_at, previous_digest, ciphertext,
                        ciphertext_digest, record_digest, encryption_algorithm,
                        encryption_key_id, nonce, integrity_algorithm,
                        integrity_key_id, signature
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        sequence,
                        recorded_at,
                        verification.last_digest,
                        ciphertext,
                        ciphertext_digest,
                        record_digest,
                        AUDIT_ENCRYPTION_ALGORITHM,
                        encryption_key_id,
                        nonce,
                        AUDIT_INTEGRITY_ALGORITHM,
                        key_id,
                        signature,
                    ),
                )
                self._connection.execute(
                    """
                    UPDATE audit_meta
                    SET last_sequence = ?, last_digest = ?
                    WHERE singleton = 1
                    """,
                    (sequence, record_digest),
                )
                self._connection.commit()
            except (AuditError, OSError, sqlite3.Error) as exc:
                self._rollback()
                if isinstance(exc, AuditError):
                    raise
                raise AuditStoreUnavailableError("audit append failed") from exc

    def verify(self) -> AuditVerificationResult:
        with self._lock:
            self._require_open()
            try:
                return self._verify_locked()[0]
            except AuditError:
                raise
            except (OSError, sqlite3.Error) as exc:
                raise AuditStoreUnavailableError("audit verification failed") from exc

    def records_for_task(self, task_id: str) -> tuple[AuditEnvelope, ...]:
        _require_opaque_id(task_id, "task_id")
        with self._lock:
            self._require_open()
            try:
                _, envelopes = self._verify_locked()
                return tuple(
                    envelope
                    for envelope in envelopes
                    if envelope.record.task_id == task_id
                )
            except AuditError:
                raise
            except (OSError, sqlite3.Error) as exc:
                raise AuditStoreUnavailableError("audit read failed") from exc

    def export_task(self, task_id: str, directory: Path) -> Path:
        records = self.records_for_task(task_id)
        destination_dir = Path(directory)
        _prepare_directory(destination_dir)
        filename = (
            f"{task_id}.json"
            if task_id.startswith("task-")
            else f"task-{task_id}.json"
        )
        destination = destination_dir / filename
        document = {
            "schema_version": AUDIT_SCHEMA_VERSION,
            "task_id": task_id,
            "integrity_status": "VERIFIED",
            "records": [record.as_dict() for record in records],
        }
        _atomic_write(destination, canonical_json(document) + b"\n")
        return destination

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._connection.close()
            self._closed = True

    def _prepare_path(self) -> None:
        _prepare_directory(self._path.parent)
        if not self._path.exists():
            try:
                descriptor = os.open(
                    self._path,
                    os.O_CREAT | os.O_EXCL | os.O_WRONLY,
                    0o600,
                )
                os.close(descriptor)
            except OSError as exc:
                raise AuditStoreUnavailableError(
                    "audit store file is unavailable"
                ) from exc
        elif self._path.is_symlink() or not self._path.is_file():
            raise AuditStoreUnavailableError("audit store path is not a file")
        _require_private_mode(self._path, "audit store")

    def _configure(self) -> None:
        self._connection.execute("PRAGMA foreign_keys = ON")
        self._connection.execute("PRAGMA trusted_schema = OFF")
        self._connection.execute("PRAGMA journal_mode = DELETE")
        self._connection.execute("PRAGMA synchronous = FULL")
        self._connection.execute("PRAGMA busy_timeout = 5000")

    def _initialize_schema(self) -> None:
        self._connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS audit_meta (
                singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                schema_version INTEGER NOT NULL,
                last_sequence INTEGER NOT NULL CHECK (last_sequence >= 0),
                last_digest TEXT
            );
            INSERT OR IGNORE INTO audit_meta (
                singleton, schema_version, last_sequence, last_digest
            ) VALUES (1, 1, 0, NULL);

            CREATE TABLE IF NOT EXISTS audit_records (
                sequence INTEGER PRIMARY KEY CHECK (sequence >= 1),
                recorded_at TEXT NOT NULL,
                previous_digest TEXT,
                ciphertext BLOB NOT NULL,
                ciphertext_digest TEXT NOT NULL,
                record_digest TEXT NOT NULL UNIQUE,
                encryption_algorithm TEXT NOT NULL,
                encryption_key_id TEXT NOT NULL,
                nonce BLOB NOT NULL UNIQUE,
                integrity_algorithm TEXT NOT NULL,
                integrity_key_id TEXT NOT NULL,
                signature TEXT NOT NULL
            );

            CREATE TRIGGER IF NOT EXISTS audit_records_no_update
            BEFORE UPDATE ON audit_records
            BEGIN
                SELECT RAISE(ABORT, 'audit records are append-only');
            END;
            CREATE TRIGGER IF NOT EXISTS audit_records_no_delete
            BEFORE DELETE ON audit_records
            BEGIN
                SELECT RAISE(ABORT, 'audit records are append-only');
            END;
            """
        )
        meta = self._connection.execute(
            "SELECT schema_version FROM audit_meta WHERE singleton = 1"
        ).fetchone()
        if meta is None or meta["schema_version"] != AUDIT_SCHEMA_VERSION:
            raise AuditIntegrityError("unsupported audit schema version")

    def _verify_locked(
        self,
    ) -> tuple[AuditVerificationResult, tuple[AuditEnvelope, ...]]:
        self._require_append_only_triggers()
        quick_check = self._connection.execute("PRAGMA quick_check").fetchone()
        if quick_check is None or quick_check[0] != "ok":
            raise AuditIntegrityError("audit database integrity check failed")
        rows = self._connection.execute(
            "SELECT * FROM audit_records ORDER BY sequence"
        ).fetchall()
        previous: str | None = None
        envelopes: list[AuditEnvelope] = []
        for expected_sequence, row in enumerate(rows, start=1):
            envelope = self._envelope(row)
            if envelope.sequence != expected_sequence:
                raise AuditIntegrityError("audit sequence is not contiguous")
            if envelope.previous_digest != previous:
                raise AuditIntegrityError("audit digest chain is broken")
            material = {
                "schema_version": AUDIT_SCHEMA_VERSION,
                "sequence": envelope.sequence,
                "recorded_at": envelope.recorded_at,
                "previous_digest": envelope.previous_digest,
                "encryption_algorithm": envelope.encryption_algorithm,
                "encryption_key_id": envelope.encryption_key_id,
                "nonce": envelope.nonce,
                "ciphertext_digest": envelope.ciphertext_digest,
            }
            if sha256_digest(material) != envelope.record_digest:
                raise AuditIntegrityError("audit record digest is invalid")
            if envelope.integrity_algorithm != AUDIT_INTEGRITY_ALGORITHM:
                raise AuditIntegrityError("audit integrity algorithm is unsupported")
            if not self._authenticator.verify(
                envelope.integrity_key_id,
                _signature_material(
                    envelope.record_digest,
                    envelope.integrity_key_id,
                ),
                _decode_signature(envelope.signature),
            ):
                raise AuditIntegrityError("audit record signature is invalid")
            previous = envelope.record_digest
            envelopes.append(envelope)

        meta = self._connection.execute(
            """
            SELECT schema_version, last_sequence, last_digest
            FROM audit_meta
            WHERE singleton = 1
            """
        ).fetchone()
        if (
            meta is None
            or meta["schema_version"] != AUDIT_SCHEMA_VERSION
            or meta["last_sequence"] != len(rows)
            or meta["last_digest"] != previous
        ):
            raise AuditIntegrityError("audit ledger head is inconsistent")
        return AuditVerificationResult(len(rows), previous), tuple(envelopes)

    def _require_append_only_triggers(self) -> None:
        rows = self._connection.execute(
            """
            SELECT name FROM sqlite_master
            WHERE type = 'trigger' AND tbl_name = 'audit_records'
            """
        ).fetchall()
        names = {row["name"] for row in rows}
        if names != {"audit_records_no_update", "audit_records_no_delete"}:
            raise AuditIntegrityError("audit append-only guards are unavailable")

    def _envelope(self, row: sqlite3.Row) -> AuditEnvelope:
        _validate_stored_timestamp(row["recorded_at"])
        if row["previous_digest"] is not None and (
            not isinstance(row["previous_digest"], str)
            or not _DIGEST.fullmatch(row["previous_digest"])
        ):
            raise AuditIntegrityError("audit previous digest is invalid")
        if not isinstance(row["record_digest"], str) or not _DIGEST.fullmatch(
            row["record_digest"]
        ):
            raise AuditIntegrityError("audit record digest is invalid")
        if (
            not isinstance(row["integrity_key_id"], str)
            or not _OPAQUE_ID.fullmatch(row["integrity_key_id"])
        ):
            raise AuditIntegrityError("audit integrity key id is invalid")
        if (
            not isinstance(row["encryption_key_id"], str)
            or not _OPAQUE_ID.fullmatch(row["encryption_key_id"])
        ):
            raise AuditIntegrityError("audit encryption key id is invalid")
        nonce = _require_blob(row["nonce"], "nonce")
        ciphertext = _require_blob(row["ciphertext"], "ciphertext")
        ciphertext_digest = _bytes_digest(ciphertext)
        if ciphertext_digest != row["ciphertext_digest"]:
            raise AuditIntegrityError("audit ciphertext digest is invalid")
        if row["encryption_algorithm"] != AUDIT_ENCRYPTION_ALGORITHM:
            raise AuditIntegrityError("audit encryption algorithm is unsupported")
        raw_payload = self._cipher.decrypt(
            row["encryption_key_id"],
            nonce,
            ciphertext,
            _encryption_context(
                sequence=row["sequence"],
                recorded_at=row["recorded_at"],
                previous_digest=row["previous_digest"],
                key_id=row["encryption_key_id"],
            ),
        )
        try:
            value = loads(raw_payload)
        except StrictJsonError as exc:
            raise AuditIntegrityError("audit payload JSON is invalid") from exc
        if not isinstance(value, dict) or canonical_json(value) != raw_payload:
            raise AuditIntegrityError("audit payload is not canonical")
        record = RouteAuditRecord.from_mapping(value)
        return AuditEnvelope(
            sequence=row["sequence"],
            recorded_at=row["recorded_at"],
            previous_digest=row["previous_digest"],
            record_digest=row["record_digest"],
            integrity_algorithm=row["integrity_algorithm"],
            integrity_key_id=row["integrity_key_id"],
            encryption_algorithm=row["encryption_algorithm"],
            encryption_key_id=row["encryption_key_id"],
            nonce=_encode_bytes(nonce),
            ciphertext_digest=ciphertext_digest,
            signature=row["signature"],
            record=record,
        )

    def _rollback(self) -> None:
        try:
            self._connection.rollback()
        except sqlite3.Error:
            pass

    def _require_open(self) -> None:
        if self._closed:
            raise AuditStoreUnavailableError("audit store is closed")

    def _close_after_failed_open(self) -> None:
        connection = getattr(self, "_connection", None)
        if connection is not None:
            connection.close()
        self._closed = True


def _validate_record(record: RouteAuditRecord) -> None:
    if record.stage not in _STAGES:
        raise AuditRecordError("audit stage is invalid")
    if not _PROTOCOL_VERSION.fullmatch(record.protocol_version):
        raise AuditRecordError("audit protocol version is invalid")
    for name in ("request_id", "task_id", "span_id", "device_id"):
        _require_opaque_id(getattr(record, name), name)
    if not _TOOL.fullmatch(record.tool):
        raise AuditRecordError("audit tool is invalid")
    if not isinstance(record.attempt, int) or isinstance(record.attempt, bool):
        raise AuditRecordError("audit attempt is invalid")
    if record.attempt not in range(1, 33):
        raise AuditRecordError("audit attempt is invalid")
    if not _DIGEST.fullmatch(record.parameter_digest):
        raise AuditRecordError("audit parameter digest is invalid")
    for name in (
        "permission_decision_id",
        "precondition_state_id",
        "before_state_id",
        "after_state_id",
    ):
        value = getattr(record, name)
        if value is not None:
            _require_opaque_id(value, name)
    if record.action_digest is not None and not _DIGEST.fullmatch(record.action_digest):
        raise AuditRecordError("audit action digest is invalid")
    if record.effective_risk is not None and record.effective_risk not in _RISKS:
        raise AuditRecordError("audit effective risk is invalid")
    if (
        record.execution_status is not None
        and record.execution_status not in _EXECUTION_STATUSES
    ):
        raise AuditRecordError("audit execution status is invalid")
    if record.outcome_code is not None and not _ERROR_CODE.fullmatch(
        record.outcome_code
    ):
        raise AuditRecordError("audit outcome code is invalid")
    if record.redaction_profile != AUDIT_REDACTION_PROFILE:
        raise AuditRecordError("audit redaction profile is unsupported")
    if record.permission_decision_id is None:
        raise AuditRecordError("audit permission decision id is required")
    if record.stage == "AUTHORIZED":
        if (
            record.action_digest is None
            or record.effective_risk is None
            or record.execution_status is not None
            or record.outcome_code is not None
            or record.before_state_id is not None
            or record.after_state_id is not None
        ):
            raise AuditRecordError("authorized audit record fields are inconsistent")
    elif (
        record.action_digest is not None
        or record.effective_risk is not None
        or record.execution_status is None
    ):
        raise AuditRecordError("result audit record fields are inconsistent")


def _require_opaque_id(value: object, name: str) -> None:
    if not isinstance(value, str) or not _OPAQUE_ID.fullmatch(value):
        raise AuditRecordError(f"audit {name} is invalid")


def _event_identity(record: RouteAuditRecord) -> tuple[str, int, str]:
    return record.request_id, record.attempt, record.stage


def _utc_timestamp(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() != timezone.utc.utcoffset(value):
        raise AuditRecordError("audit clock must return UTC")
    return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace(
        "+00:00", "Z"
    )


def _validate_stored_timestamp(value: object) -> None:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise AuditIntegrityError("audit timestamp is invalid")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise AuditIntegrityError("audit timestamp is invalid") from exc
    if parsed.utcoffset() != timezone.utc.utcoffset(parsed):
        raise AuditIntegrityError("audit timestamp is invalid")


def _signature_material(record_digest: str, key_id: str) -> bytes:
    return canonical_json(
        {
            "algorithm": AUDIT_INTEGRITY_ALGORITHM,
            "key_id": key_id,
            "record_digest": record_digest,
        }
    )


def _encryption_context(
    *,
    sequence: int,
    recorded_at: str,
    previous_digest: str | None,
    key_id: str,
) -> bytes:
    return canonical_json(
        {
            "schema_version": AUDIT_SCHEMA_VERSION,
            "sequence": sequence,
            "recorded_at": recorded_at,
            "previous_digest": previous_digest,
            "algorithm": AUDIT_ENCRYPTION_ALGORITHM,
            "key_id": key_id,
        }
    )


def _bytes_digest(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def _require_blob(value: object, name: str) -> bytes:
    if not isinstance(value, bytes):
        raise AuditIntegrityError(f"audit {name} storage type is invalid")
    return value


def _encode_bytes(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _encode_signature(value: bytes) -> str:
    if not isinstance(value, bytes) or len(value) != 32:
        raise AuditIntegrityError("audit authenticator returned an invalid signature")
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _decode_signature(value: object) -> bytes:
    if not isinstance(value, str) or len(value) != 43:
        raise AuditIntegrityError("audit signature encoding is invalid")
    try:
        decoded = base64.b64decode(value + "=", altchars=b"-_", validate=True)
    except (ValueError, UnicodeEncodeError) as exc:
        raise AuditIntegrityError("audit signature encoding is invalid") from exc
    if len(decoded) != 32:
        raise AuditIntegrityError("audit signature encoding is invalid")
    return decoded


def _prepare_directory(path: Path) -> None:
    try:
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
    except OSError as exc:
        raise AuditStoreUnavailableError("audit directory is unavailable") from exc
    if path.is_symlink() or not path.is_dir():
        raise AuditStoreUnavailableError("audit directory path is invalid")
    _require_private_mode(path, "audit directory")


def _require_private_mode(path: Path, description: str) -> None:
    if os.name == "nt":
        return
    try:
        mode = stat.S_IMODE(path.stat().st_mode)
    except OSError as exc:
        raise AuditStoreUnavailableError(
            f"{description} metadata is unavailable"
        ) from exc
    if mode & 0o077:
        raise AuditStoreUnavailableError(f"{description} permissions are too broad")


def _atomic_write(path: Path, content: bytes) -> None:
    descriptor: int | None = None
    temporary: str | None = None
    try:
        descriptor, temporary = tempfile.mkstemp(
            prefix=f".{path.name}.", dir=path.parent
        )
        if hasattr(os, "fchmod"):
            os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            descriptor = None
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        temporary = None
        if os.name != "nt":
            directory = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
    except OSError as exc:
        raise AuditStoreUnavailableError("audit export failed") from exc
    finally:
        if descriptor is not None:
            try:
                os.close(descriptor)
            except OSError:
                pass
        if temporary is not None:
            try:
                os.unlink(temporary)
            except OSError:
                pass
