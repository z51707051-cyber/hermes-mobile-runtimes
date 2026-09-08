# HMR-106 Minimal PhoneState/Observer Verification

HMR-106 implements ADR-0004's first coherent PhoneState generation without
expanding the Android Accessibility authority introduced by HMR-105.

## Delivered contract

- Protocol patch `0.1.1` and an exact schema-bundle digest.
- An immutable state id for every accepted foreground callback.
- `previous_state_id` linking consecutive observations in one connected
  service generation.
- Foreground package and nullable Accessibility class/activity claim.
- Monotonic freshness and UTC capture time.
- Explicit `COMPLETE`, `PARTIAL` or `INCOHERENT` capture status plus bounded
  stable error codes.
- A typed `WINDOW_IDENTITY` fingerprint over length-prefixed package/activity
  values.
- Deterministic `UNKNOWN`, `NONE` and `CHANGED` transition semantics.

The state reference contains no UI text, hierarchy, screenshot bytes, path,
token or raw Android object. `WINDOW_IDENTITY` does not detect content changes
inside one Activity and is not accepted as screenshot verification.

## Lifecycle invariants

The Observer creates every foreground generation in one synchronized critical
section. Invalid packages are rejected. Missing or invalid activity claims
produce `PARTIAL` plus `FOREGROUND_ACTIVITY_UNAVAILABLE` rather than a fake
value.

Disconnect clears the last generation. After reconnect,
`phone.current_app` remains unavailable until Android publishes a new valid
window event. State older than the configured maximum is unavailable.

## Protocol evidence

The shared golden fixtures include:

- a complete non-null PhoneState result accepted by Python and Kotlin; and
- an incomplete PhoneState missing `capture_status`, rejected by both codecs;
- a first state that falsely claims `CHANGED`, rejected by both codecs; and
- a state bound to a different device, rejected by both codecs.

Changing the common schema changes its file digest and full bundle digest.
Compatibility negotiation requires exact digests and patch `0.1.1`; an old
`0.1.0` peer fails closed.

## Behavior evidence

Kotlin tests prove:

- no state is available before service connection and a valid callback;
- first, identical and different fingerprints produce `UNKNOWN`, `NONE` and
  `CHANGED`;
- every accepted callback creates a new id and predecessor link;
- missing activity is explicit partial state;
- stale and disconnected observations fail with typed reasons;
- reconnect does not resurrect a pre-disconnect state;
- `phone.current_app` returns all required `0.1.1` fields through the Android
  Router and PEP.

Python/contract tests prove schema integrity, version compatibility, strict
round-trip behavior and Runtime audit correlation with the new state ids.

## Commands

```bash
python3 scripts/mobile/generate_protocol_manifest.py --check
scripts/run_tests.sh -j 4 tests/mobile -q

cd apps/mobile-bridge-android
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug cyclonedxBom
```

CI must also run the source and built-APK manifest policy, dependency
verification and SBOM checks. HMR-113 owns API 30/API 36 service lifecycle
instrumentation; HMR-108/109 own UI-tree, screenshot and true content-derived
fingerprints.
