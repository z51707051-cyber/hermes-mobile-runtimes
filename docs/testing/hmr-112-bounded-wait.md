# HMR-112 Bounded Wait Verification

HMR-112 registers `phone.wait` as the thirteenth V0.1 Android provider. This
test plan covers timer completion, semantic conditions, absolute deadlines,
authorization expiry, cancellation, incomplete observations and capability
revocation.

## Automated coverage

`WaitProviderTest` verifies:

- a timer wait finishes at its requested bound without Accessibility capture;
- `STATE_CHANGED` uses comparable semantic fingerprints;
- foreground-package and protected-text conditions may pass immediately;
- partial semantic evidence cannot satisfy a condition;
- condition timeout is typed and does not retry an action;
- the signed action/authorization bound truncates a longer timer;
- cancellation terminates within one 100 ms poll interval;
- Accessibility disconnect races return a schema-valid typed failure;
- the Android PEP requires semantic authority only for conditional waits;
- duplicate active request ids fail closed; and
- out-of-schema wait parameters never reach the provider.

`PhoneStateObserverTest` additionally proves that successive semantic probes
produce comparable transitions without publishing artifact references.

The Android CI lane must run:

```bash
cd apps/mobile-bridge-android
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug cyclonedxBom
```

It also runs Python protocol/Router tests, schema-manifest verification,
manifest policy checks, APK manifest inspection and SBOM generation.

## Device/emulator follow-up

HMR-113 must exercise the same provider against deterministic slow-page,
dialog and changed-content fixtures. It must verify cancellation from the
production task/session transport once that transport exists. HMR-112 exposes
only an internal process-local cancellation seam and does not claim an
externally reachable command channel.
