# Hermes Mobile Android Bridge

This directory is the Android execution-plane boundary defined by
[`ARCHITECTURE.md`](../../ARCHITECTURE.md). HMR-101 provided the reproducible
build foundation, HMR-102 added the reviewed device-security kernel,
HMR-103 added the closed protocol codec and HMR-104 added fail-closed routing.
HMR-105 adds `phone.current_app`, HMR-106 adds coherent PhoneState, HMR-108
adds bounded `phone.read_screen`, HMR-109 adds protected screenshot capture,
HMR-110 adds state-bound navigation with post-action verification, HMR-111
adds protected notification/device-state observation, and HMR-112 adds a
bounded cancellable wait. HMR-113 adds isolated emulator contracts around the
packaged bridge, HMR-114 adds blocking CI gates, HMR-115 adds the first scoped
in-process Hermes Agent tool route, and HMR-116 adds the launcher task session
and embedded-runtime boundary. HMR-117 moves that session into a private,
user-visible foreground lifecycle so it can continue after the launcher is
covered or the screen turns off. HMR-118 adds one task-scoped system-picker
attachment grant and an exact-package share-flow launcher without exposing an
arbitrary URI or Intent surface. The initial target allowlist is WeChat and QQ.
HMR-119 packages the same reviewed bridge with the real Hermes Agent and an
ARM64-only Chaquopy runtime as a separately built, installable internal alpha
for the target iQOO Z10x. Its build definition lives in
`apps/hermes-mobile-internal`; it reuses this module's production manifest,
sources and resources rather than maintaining a second authority surface.

Chinese installation and device acceptance instructions are in
[`android-developer-preview-zh.md`](../../docs/testing/android-developer-preview-zh.md).
The launcher provides an in-memory chat task surface, model settings, explicit
stop-and-revoke control and system permission settings. The ordinary bridge APK
fails closed when the embedded bundle is absent. The complete HMR-116 APK loads
the Chaquopy backend through the same launcher boundary and proves one real
Agent `phone_wait` call through the production Router on Android 15. The
HMR-117 foreground service keeps an accepted task alive across Activity
recreation, while its private notification can stop the task and immediately
revoke the Android tool bridge.

The HMR-119 internal artifact is fixed-key-signed, non-debuggable and ARM64-only.
CI verifies its public signing-certificate fingerprint so later internal builds
remain Android upgrade-compatible. It is the first
package intended for physical-device acceptance, but it remains incomplete:
`cryptography`, Pillow and psutil dependent paths are withheld until their
exact Android wheels and behavior pass the same review gates.

## HMR-112 bounded execution boundary

The debug APK deliberately has:

- exactly the reviewed network, foreground-service, notification and bounded
  wake-lock permissions required by the on-device task lifecycle;
- cleartext disabled in the manifest and network-security configuration;
- a P-256 device identity whose private key remains in Android Keystore;
- a strict TLS 1.3/1.2 policy and closed `/v0/enroll` HTTPS endpoint parser;
- a crash-safe, no-backup sequence/nonce replay ledger;
- strict bounded JSON parsing with duplicate-key rejection;
- fail-closed compatibility negotiation and canonical action digests;
- verification of the normative Python schema-bundle manifest;
- a closed 14-tool capability catalog with immutable minimum risk levels;
- an Android Router that accepts only `AuthorizedAction` and calls its PEP
  before provider resolution;
- a deny-all default authorization PEP and a live capability check immediately
  before dispatch;
- one system-bound Accessibility service that listens only for
  `TYPE_WINDOW_STATE_CHANGED`, retaining package/activity/window identity;
- active-window content retrieval with only `flagReportViewIds`;
- on-demand normalized semantic capture bounded to 500 nodes, 20,000 text
  characters, depth 64 and one active window;
- mandatory password-content withholding, L4 manual handoff for password and
  verification-code fields, authentication-secret withholding in notification
  capture, and encrypted five-minute D3 in-memory artifacts with a separately
  keyed digest;
- on-demand PNG/lossless-WebP screenshot capture with bounded crop, pixels,
  callback time and 16 MiB encoded output;
- exact foreground-state correlation and typed secure-window, permission,
  timeout, rate-limit, oversize and transition failures;
- state-bound semantic tap, long press and type with HMAC descriptor
  re-resolution before policy and execution;
- fixed Back/Home actions, bounded long-press/swipe gestures, and explicit
  exact-package launcher Intents;
- MAIN/LAUNCHER package visibility only, with no `QUERY_ALL_PACKAGES`;
- device-side risk upgrade, unconditional L4/L5 denial, and bounded
  post-action semantic verification;
- one separately system-bound Notification Listener with a bounded memory-only
  ledger, HMAC ids, dedupe and process-session cursors;
- protected D3 notification and minimized D2 device-state artifacts;
- bounded timer/semantic waits capped by request deadline and authorization
  expiry, with process-local cancellation and no automatic action retry;
- artifact-free semantic wait probes with process-keyed fingerprints and no
  retained or returned UI plaintext;
- no notification actions, Event Bus, receiver, content provider, SSID/BSSID,
  IP, location, nearby-device or stable-device observation;
- no enrollment listener, protocol command route or raw device endpoint;
- a revocable per-user-task Agent bridge with an in-memory P-256 signing key,
  ten-second action authorization, strict sequence/nonce replay rejection and
  a 1,024-action session bound;
- 14 provider-safe `phone_*` Hermes tool registrations which remain invisible
  until Android injects the current task bridge;
- one in-memory, one-task attachment selection consumed by
  `phone_share_attachment`; the model can choose only the reviewed WeChat/QQ
  target package, never a path, URI, MIME type or arbitrary Intent field;
- one exported launcher activity with a bounded single-task chat session;
- one non-exported `dataSync` task service, a private persistent notification,
  and a non-reference-counted wake lock capped at 16 minutes;
- no code copied or adapted from `hermes-android`.

The current-app Provider emits a schema-valid `ToolExecutionResult` with the
same coherent foreground state as `before_state` and `after_state` for the
read-only observation. Protocol `0.1.1` includes package/activity,
predecessor, capture completeness/errors and a typed window-identity
fingerprint. The fingerprint detects only package/activity transitions and is
not screenshot verification. A disconnected, empty or stale observer is a
typed unavailable capability, never an empty-success result; reconnect also
requires a new event.

The read-screen Provider emits a new UI-hierarchy PhoneState generation and a
closed `ArtifactRef`; raw tree content never enters Tool JSON, logs or Audit.
The process-local artifact store intentionally has no direct read method.
Authorized and audited retrieval will be composed with the production bridge
transport rather than exposed as an in-process bypass.

The screenshot Provider likewise returns only a protected `ArtifactRef` and a
new `SCREENSHOT` PhoneState fingerprint. A live active root revalidates
package/window identity; if the state changes while capture is pending, the
artifact is deleted.

Navigation is not a raw gesture channel. The service enables gestures only so
the closed providers can perform schema-bounded long presses and swipes after
Router/PEP authorization. Semantic targets are short-lived, re-resolved from a
fresh complete tree and classified on device. Coordinate taps, arbitrary
Accessibility action ids, caller-controlled Intent fields and L4/L5 actions
remain unavailable. Every accepted mutation observes again; observation
failure becomes `UNKNOWN_OUTCOME` rather than an automatic retry.

The APK exposes no network listener or Binder command surface. Its task service
is private and has no intent filter. The default PEP still denies every action.
HMR-115/HMR-116 can inject only a process-local, revocable user-task verifier;
stopping a task closes the bridge immediately. The model receives a narrow JSON
tool object, never the signing key or serialized `AuthorizedAction`.

The `fixture-app` module and `app/src/androidTest` runner exist only for
HMR-113 CI. They use synthetic text, are built as separate test APKs and are
never included in the release bridge. API 30/API 36 clean emulator lanes grant
and revoke the exact Accessibility service, then exercise slow content,
dialogs, a verified Back action, IME visibility and semantic UI drift through
the real Router/PEP path. The HMR-116 probe composes the production launcher,
task-runtime interface and Chaquopy backend with the real embedded `AIAgent`;
its deterministic model searches and describes `phone_wait`, invokes it,
verifies the structured Android result and completes the final model turn
without external credentials or paid tokens.

The Kotlin codec depends on the pinned stable Moshi `1.15.2` release. Normative
schemas and cross-language fixtures remain in the repository root; Android
does not maintain a divergent generated copy. The codec validates data only:
it does not dispatch. The separate Router is the only provider invocation
path, and no transport listener exposes that Router to Hermes or the network.

Device capabilities must be registered only behind the Router after their
protocol contract, Runtime authorization path and Android PEP checks are
reviewed. In particular, do not add Shell, Shizuku, APK installation,
arbitrary Intent, SMS, call, microphone or location authority to this module.

## Pinned toolchain

| Component | Pin |
|---|---:|
| Android Gradle Plugin | 9.3.2 |
| Gradle wrapper | 9.5.0 |
| JDK | 17 |
| Android build tools | 36.0.0 |
| `compileSdk` / `targetSdk` | 36 |
| `minSdk` | 30 |

The wrapper distribution and wrapper JAR are SHA-256 checked. Gradle dependency
verification and locking are mandatory after their reviewed metadata is
committed. CI generates both a build-wide CycloneDX JSON/XML SBOM and a direct
APK SBOM restricted to `debugRuntimeClasspath`.

## Build and verify

Install JDK 17, Android platform 36 and build tools 36.0.0, then run:

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

CI additionally decodes the built APK manifest with `apkanalyzer`, checks the
final merged manifest, records the packaged-file inventory and publishes the
APK SHA-256, direct APK SBOM and build-wide SBOM as review artifacts.

The committed Gradle lock and verification metadata are generated by the
workflow bootstrap pass, reviewed, then enforced in strict mode. They must
never be hand-written.
