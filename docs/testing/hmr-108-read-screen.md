# HMR-108 `phone.read_screen` Verification

HMR-108 implements the bounded semantic capture accepted in ADR-0008. It
widens the existing Accessibility service to active-window reads but does not
enable gestures, all-window inspection or a transport listener.

## Delivered behavior

- Protected `phone.read_screen` provider behind the existing Router and PEP.
- Active package/window correlation with a new immutable PhoneState generation.
- Flat semantic node normalization with node, text, depth and child limits.
- Mandatory password-content redaction and safe Unicode truncation.
- AES-256-GCM in-memory D3 artifact storage with a separately keyed digest,
  five-minute expiry and no plaintext retrieval escape hatch.
- Schema-valid result containing the same protected ArtifactRef in result and
  PhoneState metadata.
- Recoverable typed failures for disconnected, missing or changed windows.

## Automated evidence

Android unit tests cover normalized roles/text, password withholding, Unicode
and node limits, artifact key separation/expiry/nonce reuse, PhoneState window
correlation, full Router/PEP/provider dispatch and disconnect races.

The Python manifest-policy tests and built-APK inspection require:

- only `typeWindowStateChanged` events;
- exactly `flagReportViewIds`;
- `canRetrieveWindowContent=true`;
- `canPerformGestures=false`; and
- no additional Android permission or component.

## Commands

```bash
python3 scripts/android/verify_android_manifest_policy.py \
  --network-security-config apps/mobile-bridge-android/app/src/main/res/xml/network_security_config.xml \
  --accessibility-service-config apps/mobile-bridge-android/app/src/main/res/xml/current_app_accessibility_service.xml \
  --full-backup-rules apps/mobile-bridge-android/app/src/main/res/xml/backup_rules.xml \
  --data-extraction-rules apps/mobile-bridge-android/app/src/main/res/xml/data_extraction_rules.xml \
  apps/mobile-bridge-android/app/src/main/AndroidManifest.xml

cd apps/mobile-bridge-android
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The CI Android lane is authoritative. A production separately authorized
artifact retrieval operation remains required before a remote planner can
consume the encrypted tree.
