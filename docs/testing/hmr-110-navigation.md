# HMR-110 Navigation Verification

HMR-110 adds protected providers for tap, long press, type, swipe, back, home
and open app. ADR-0010 defines the trust and failure semantics.

## Automated evidence

Protocol contract tests prove that tap/long/type targets and both swipe points
bind to the exact `state_precondition` generation in Python and Kotlin.

Android unit tests cover:

- target-registry HMAC re-resolution, expiry, descriptor drift and fail-closed
  icon-only classification;
- L1/L2/L3/L4 semantic classification, including Chinese risk terms;
- exact Android PEP state, package and effective-target checks;
- L3 risk upgrade before provider invocation;
- unconditional L4 denial until device authentication exists;
- successful execution with distinct before/after state and verification;
- post-action observation failure becoming `UNKNOWN_OUTCOME`; and
- equivalent UI trees producing stable comparable fingerprint content; and
- schema-valid provider results with no raw UI or text evidence.

The manifest-policy suite requires the exact HMR-110 Accessibility profile:
`typeWindowStateChanged`, `flagReportViewIds`, content read, screenshot read
and gesture capability, with no unreviewed attribute, child, permission or
component. It also requires exactly one MAIN/LAUNCHER package-visibility query
and rejects package/provider queries or `QUERY_ALL_PACKAGES`. CI repeats these
checks against the compiled APK.

## Required commands

```bash
uv run --locked pytest -q tests/mobile/contract tests/mobile/unit

cd apps/mobile-bridge-android
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug cyclonedxBom
```

## Remaining emulator/device evidence

HMR-113/114 must validate exact-once gestures, app launch, input focus,
keyboard obstruction, dialog overlays, slow transitions, target drift,
Accessibility revocation, secure windows and OEM behavior on the release
matrix. It must also verify that no accepted action continues after its
deadline and that user-visible Audit distinguishes execution from verification.
