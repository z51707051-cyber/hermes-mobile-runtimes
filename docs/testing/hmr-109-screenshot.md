# HMR-109 Protected Screenshot Verification

HMR-109 adds the first protected `phone.screenshot` provider. It is an L0
read-only capability and does not add gestures, MediaProjection, storage or a
raw image endpoint.

## Automated evidence

The Android unit suite verifies:

- protocol parameters become the exact display/format/crop capture spec;
- successful results separate before/after state and expose only a D3
  `ArtifactRef` with a `SCREENSHOT` fingerprint;
- Android PEP rejects an unavailable screenshot source before provider code;
- secure-window failure is typed, non-recoverable and never silently retried;
- screenshot state publication requires the exact foreground state id;
- secondary displays fail before Android capture until display-scoped state
  is implemented;
- repeated comparable fingerprints produce `NONE` while window races fail;
- crop validation uses overflow-safe bounds arithmetic; and
- stale state stays invalid for mutation while a matching live root can
  anchor a new read capture.

The manifest policy suite requires `canTakeScreenshot=true`,
`canRetrieveWindowContent=true`, `canPerformGestures=false`, the exact
window-state event subscription and only `flagReportViewIds`. The APK lane
checks the compiled manifest in addition to source XML.

## Required commands

```bash
cd apps/mobile-bridge-android
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug cyclonedxBom
python3 ../../scripts/android/verify_android_manifest_policy.py \
  --network-security-config app/src/main/res/xml/network_security_config.xml \
  --accessibility-service-config app/src/main/res/xml/current_app_accessibility_service.xml \
  --full-backup-rules app/src/main/res/xml/backup_rules.xml \
  --data-extraction-rules app/src/main/res/xml/data_extraction_rules.xml \
  app/src/main/AndroidManifest.xml
```

CI also validates the Python protocol contract, dependency metadata, SBOM,
APK inventory and source/built-manifest authority.

## Remaining device evidence

HMR-113/114 must exercise API 30/33/35/36 and the release OEM matrix for:

- default and secondary displays where supported;
- crop boundaries and large/high-entropy screens;
- secure-window rejection;
- screenshot rate limiting and callback timeout;
- Accessibility revocation during capture;
- foreground changes during capture; and
- PNG/WebP decode and artifact expiry/deletion.

The production bridge transport must also prove separately authorized,
audited retrieval before a remote planner can consume an image, and the
release UI must surface screenshot capture in user-visible task history.
