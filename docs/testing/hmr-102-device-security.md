# HMR-102 Device Security Verification

> Phase: 1 / HMR-102
> Status: in review
> Last updated: 2026-08-29

## Scope

HMR-102 establishes the security primitives required before Android can accept
a protected Runtime action:

- accepted [`ADR-0005`](../adr/0005-device-identity-transport-and-key-rotation.md);
- Android Keystore P-256 device identity and proof signing;
- HTTPS-only enrollment endpoint policy and TLS 1.3/1.2 floor;
- persistent sequence/nonce replay defense that commits before dispatch;
- explicit backup, manifest and network-security policy.

It does not implement a broker connection, protocol codec, Accessibility,
notifications, screenshots, Intent, Shell, Shizuku or any `phone.*` tool.

## Security invariants under test

| Boundary | Required behavior |
|---|---|
| Manifest | Only `android.permission.INTERNET`; only the launcher is exported |
| Network config | Cleartext false; Android system trust only; no debug/user CA override |
| Endpoint | Exact HTTPS `/v0/enroll`; no URL credentials, query token or fragment |
| TLS | Enable only TLS 1.3 and 1.2; require HTTPS endpoint identification |
| Identity | Device id is a stable SHA-256 SPKI binding; private key is non-exportable; API 30 does not overclaim an exact hardware tier |
| Replay | Strict next sequence, 16–64-byte nonce, 30-second authorization maximum |
| Persistence | Record before accept; restart keeps state; read/write corruption fails closed |
| Backup | Keystore keys and replay/enrollment state cannot move to another device |

The replay ledger is stored below Android `noBackupFilesDir` and contains only
opaque session ids, sequence numbers, SHA-256 nonce digests and expiries. It
does not contain an authorization, pairing token or user content.

## Commands

```bash
cd apps/mobile-bridge-android
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug cyclonedxBom
python3 ../../scripts/android/verify_android_manifest_policy.py \
  --network-security-config app/src/main/res/xml/network_security_config.xml \
  --full-backup-rules app/src/main/res/xml/backup_rules.xml \
  --data-extraction-rules app/src/main/res/xml/data_extraction_rules.xml \
  app/src/main/AndroidManifest.xml
```

From the repository root, the lightweight policy suite is:

```bash
scripts/run_tests.sh tests/mobile/unit/test_android_manifest_policy.py -q
```

CI also decodes the final APK manifest, re-runs the policy against the merged
manifest, validates the SBOM and publishes the APK digest and review evidence.

## Deferred proof

HMR-103 adds the closed enrollment/protocol schemas and Python↔Kotlin golden
fixtures. Emulator proof for real Android Keystore hardware level, process
restart, backup/restore and invalid TLS peers enters the HMR-105/HMR-113 lanes
before any phone capability is enabled.
