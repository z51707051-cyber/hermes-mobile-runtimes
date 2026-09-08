# ADR-0007: Encrypted Append-Only Audit Ledger

- Status: Accepted
- Date: 2026-09-08
- Decision owners: Hermes Mobile Runtime maintainers
- Phase: 1 / HMR-107

## Context

ADR-0003 requires a trusted audit writer before protected execution. HMR-105
introduced the closed `RouteAuditRecord` seam and made audit precommit failure
block device dispatch, but its test sink was memory-only. A restart lost every
record, there was no tamper evidence, and the user could not export a task
history.

The audit store handles identifiers, selected tools, policy decisions, state
references and outcomes. Even without message text or UI content, that history
is sensitive. A plain JSON log, an unencrypted SQLite table or application
logs are not acceptable durable stores.

## Decision

### 1. Authority and scope

The durable writer is a protected Runtime dependency implementing
`ExecutionAuditSink`. The planner, model Tools, Skills and MCP servers receive
neither the database handle nor its cryptographic keys.

HMR-107 persists two route stages:

- `AUTHORIZED`, committed before Android dispatch; and
- `RESULT`, committed after a validated policy or device result.

The closed record carries protocol, task/request/span/device ids, Tool,
attempt, parameter/action digests, permission decision, effective risk,
precondition/before/after state ids, execution status and stable outcome code.
It has no field for raw parameters, UI data, notification text, exception
text, credentials or model-supplied audit prose.

Intent and plan history are not fabricated from route metadata. The Hermes
control-plane adapter must later emit separately typed task/plan records before
the full user-facing Audit requirement is complete.

### 2. Durable transaction store

The first implementation uses Python's SQLite with:

- atomic `BEGIN IMMEDIATE` transactions;
- `synchronous=FULL` and rollback journaling;
- a contiguous global sequence and committed ledger head;
- a five-second busy timeout;
- a full SQLite integrity check before verification;
- database mode `0600` inside a private directory; and
- triggers that reject update or delete of authoritative records.

SQLite is a storage primitive, not a trust boundary. The protected service
identity and filesystem permissions remain required.

### 3. Encryption and key separation

Every canonical record payload is encrypted with AES-256-GCM and a fresh
96-bit nonce. Sequence, capture time, predecessor digest, encryption algorithm
and key id are bound as associated data. The database does not keep plaintext
task, request, device, Tool or policy fields as query columns.

Encryption and integrity use separate injected keyrings. New entries use the
active key id; verification and export require the historical keys referenced
by older records. Key bytes never enter the database, export or error text.
Production key loading belongs to the isolated broker identity and must not be
implemented as a non-secret `config.yaml` value or model-readable Tool.

### 4. Authenticated digest chain

Each row commits the previous row digest, sequence, UTC time, encryption
metadata, nonce and ciphertext digest. The resulting SHA-256 record digest is
authenticated with HMAC-SHA256. Verification checks every row, the contiguous
sequence, predecessor link, AEAD tag, canonical closed record, HMAC and ledger
head before any read, export or new append.

The chain detects modification, insertion and interior deletion without the
keys. It does not by itself prove freshness against rollback of the entire
database to a previously valid snapshot. A production deployment requiring
rollback resistance must anchor signed heads outside this database (for
example in a broker-managed remote/WORM checkpoint). Until that anchor exists,
the product must describe the ledger as tamper-evident, not rollback-proof.

### 5. Idempotency and conflicts

The logical event identity is `(request_id, attempt, stage)`. An exact replay
is a successful no-op. Reusing that identity with different content is an
`AuditConflictError`; it never creates two contradictory histories.

### 6. Failure behavior

Open, verification, encryption, signature, transaction, permissions and disk
failures raise a typed audit error. The Runtime converts any append failure to
the safe `AUDIT_UNAVAILABLE` route error. HMR-107 retains the stricter HMR-105
behavior: every routed risk level fails closed if the required precommit is
not durable.

A failed append rolls back its transaction. There is no best-effort plaintext
fallback and no silent log-only mode.

### 7. User-facing task export

After full-ledger verification, the store can atomically generate
`audit/task-{id}.json` with mode `0600`. This is a replaceable read model, not
the authoritative append-only database. It contains decrypted closed metadata
and integrity envelopes, never raw action parameters or protected artifacts.

### 8. Retention

HMR-107 does not delete authoritative rows because deletion would invalidate
the append-only chain. Retention, archival and cryptographic-erasure policy
must be implemented with signed segment checkpoints in a follow-up before
long-term production use. D3 UI/screenshot artifacts remain outside this
ledger and keep ADR-0004's shorter lifecycle.

## Consequences

### Positive

- Required pre-execution records survive process restart atomically.
- Plain database inspection does not reveal route identifiers or Tools.
- Altered ciphertext, metadata, chain links or signatures fail closed.
- Exact retries do not create duplicate audit events.
- Key rotation can preserve verification with explicit historical keys.
- Users receive a predictable per-task JSON export.

### Costs and limits

- Full-chain verification is linear and intentionally favors correctness over
  throughput in V0.1.
- Task export decrypts and scans the small V0.1 ledger instead of maintaining a
  plaintext query index.
- Whole-database rollback needs an external head anchor.
- Task intent/plan ingestion and segmented retention remain required work.

## Rejected alternatives

### Plain JSON or JSONL as the authoritative log

Rejected because crash-safe multi-step append, concurrency and protected
querying would be reimplemented poorly.

### Plain SQLite columns plus a hash chain

Rejected because task, device and Tool history would remain readable at rest.

### One encryption key for confidentiality and integrity

Rejected because ADR-0005 requires purpose-separated key authorities and key
rotation would become ambiguous.

### Store raw parameters and redact during export

Rejected because compromise, diagnostics and backup would expose content that
the durable store never needed.

### Continue execution when audit append fails

Rejected for HMR-107. A later signed gap-marker design may selectively relax
L0–L2 under ADR-0003, but no trusted gap writer exists today.

## Compliance checks

1. Router precommit failure prevents device dispatch.
2. Restart preserves and verifies the sequence and ledger head.
3. The database contains no plaintext parameter, task or request value.
4. AEAD/HMAC/chain tampering fails before read, export or append.
5. SQL update/delete is rejected by append-only triggers.
6. Exact event replay is idempotent; conflicting replay fails.
7. Historical encryption and integrity keys verify records across rotation.
8. Export is atomic, private and contains only closed redacted metadata.

