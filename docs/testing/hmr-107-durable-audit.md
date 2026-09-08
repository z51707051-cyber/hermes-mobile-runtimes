# HMR-107 Encrypted Audit Verification

HMR-107 replaces the memory-only execution Audit seam with the durable design
accepted in ADR-0007. It does not add an Android permission, transport listener
or model-facing Tool.

## Delivered behavior

- SQLite atomic append with full synchronous durability.
- Closed `AUTHORIZED` and `RESULT` records with attempt and state correlation.
- AES-256-GCM encrypted canonical payloads.
- Separate, rotation-aware HMAC-SHA256 integrity authentication.
- Contiguous sequence, predecessor digest chain and verified ledger head.
- Update/delete guards and exact-replay idempotency.
- Private atomic `audit/task-{id}.json` export after full verification.
- Safe failure propagation through the existing `AUDIT_UNAVAILABLE` route.

The database has no plaintext task, request, device, Tool, parameters, UI or
notification content. Export exposes only the reviewed closed metadata schema.

## Automated evidence

`tests/mobile/unit/test_audit_store.py` proves:

- append, close, reopen, verify and export;
- ciphertext-at-rest and private file permissions;
- exact replay versus conflicting replay;
- SQL append-only guards and ciphertext tamper detection;
- independent encryption/integrity key rotation;
- invalid record/key rejection; and
- closed-store failure instead of silent audit loss.

`tests/mobile/unit/test_runtime_router.py` continues to prove that the
authorization record commits before Android dispatch, terminal results link
their state ids and audit failure stops execution.

## Commands

```bash
scripts/run_tests.sh -j 4 tests/mobile -q

ruff check hermes_mobile tests/mobile
ruff format --check hermes_mobile tests/mobile
```

The mobile GitHub workflow is authoritative when a local locked environment is
unavailable. Whole-database rollback resistance and segmented retention remain
explicit follow-ups; HMR-107 does not claim either property.
