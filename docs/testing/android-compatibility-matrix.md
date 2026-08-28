# Android Compatibility and Device Test Matrix

- Status: Phase 1 baseline
- Date: 2026-08-28
- Owner: HMR-006
- Applies to: Hermes Mobile Runtime V0.1 Android bridge and all 13 `phone.*` tools

## 1. Decision

Hermes Mobile Runtime V0.1 supports **Android 11 / API 30 and later** on the
primary personal profile. The initial release build baseline is:

| Setting | Decision | Reason |
|---|---|---|
| `minSdk` | 30 | `AccessibilityService.takeScreenshot` was added in API 30 and screenshot is a required V0.1 capability. |
| `compileSdk` | 36 | Android 16 is the current stable platform baseline. |
| `targetSdk` | 36 | Google Play requires new apps and updates to target API 36 from 2026-08-31. |
| JDK | 17 | Required by the selected Android build tooling generation and consistent with the inspected upstream. |
| Emulator CI ABI | x86_64 | Fast, repeatable CI execution. |
| Physical test ABI | arm64-v8a | Representative of supported phones; the bridge remains ABI-neutral unless a reviewed native dependency is added. |

HMR-101 must select and pin a stable API-36-compatible Android Gradle Plugin,
Gradle wrapper, Kotlin version, SDK packages and checksums. The inspected
`hermes-android` baseline uses AGP 8.3, Gradle 8.6, Kotlin 1.9.22 and
`compileSdk`/`targetSdk` 34; that toolchain is evidence about upstream, not the
toolchain to copy unchanged.

Android 17 / API 37 remains a **non-blocking canary** while the official SDK is
published as a preview. It becomes a blocking lane only after the SDK/toolchain
is stable and an explicit compatibility review is merged.

Sources:

- [Google Play target API requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [Set up the Android 16 SDK](https://developer.android.com/about/versions/16/setup-sdk)
- [Set up the Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk)
- [`AccessibilityService.TakeScreenshotCallback` API reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.TakeScreenshotCallback)
- [`hermes-android` application build configuration at the audited commit](https://github.com/raulvidis/hermes-android/blob/fbd623840bdcf1b38c835c7d2973c7b667d55da8/hermes-android-bridge/app/build.gradle.kts)

## 2. Scope and support vocabulary

This matrix tests platform capability and Runtime behavior; it is not a claim
that every third-party app UI is supported.

| Term | Meaning |
|---|---|
| Supported | Release-blocking tests pass and a typed degradation path exists for optional or user-revocable capabilities. |
| Canary | Failures are triaged but do not block release until promoted by an explicit decision. |
| Unsupported | The Runtime returns a typed error and must not silently substitute a broader mechanism. |
| Inaccessible by design | Android or project policy intentionally prevents access; the Runtime must not attempt bypasses. |

V0.1 supports only the device owner's primary personal profile. The following
are not V0.1 support claims:

- managed/work-profile notifications or cross-profile actions;
- locked Private Space content;
- waking, unlocking or authenticating the device for the user;
- windows protected by `FLAG_SECURE` or equivalent policy;
- arbitrary app discovery, arbitrary Intent, Shell, Shizuku or MediaProjection
  fallback.

Android documents that notification listeners are ignored in work profiles
and may be restricted by device policy. Android 15 also hides Private Space
apps and notifications while that space is locked. These cases are capability
or policy boundaries, not recovery opportunities.

Sources:

- [`NotificationListenerService` API reference](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [Android 15 feature and change summary](https://developer.android.com/about/versions/15/summary)

## 3. Release matrix

### 3.1 Emulator lanes

Every emulator starts from a clean data state for permission/lifecycle tests.
Release evidence must identify the system-image package and digest; mutable
developer snapshots are not acceptable evidence.

| API | Android | Lane | Frequency | Required coverage |
|---:|---:|---|---|---|
| 30 | 11 | Blocking minimum | Every PR: vertical slice; nightly: full | Install, Accessibility, UI tree, screenshot, gestures, notification grant/revoke, exact-package launch. |
| 31 | 12 | Boundary | Nightly | Background foreground-service restrictions and process/lifecycle recovery. |
| 33 | 13 | Blocking representative | Nightly and release | Restricted-settings onboarding for sideloads, notification lifecycle, permission revoke, modern IME behavior. |
| 34 | 14 | Boundary | Nightly | Foreground-service type/permission compliance and background restrictions. |
| 35 | 15 | Blocking representative | Nightly and release | Private Space boundary, stricter Intent/background launch behavior, current app/UI flows. |
| 36 | 16 | Blocking latest stable | Every PR: vertical slice; nightly/release: full | Target-SDK behavior, all V0.1 capabilities, local-network restriction compatibility test. |
| 37 | 17 | Non-blocking canary | Scheduled/nightly when image is available | Preview regressions, mandatory local-network permission behavior, protocol/device reconnect. |

The every-PR vertical slice is `phone.current_app` plus capability negotiation,
permission denial, audit correlation and one verified navigation action. HMR-113
defines the deterministic fixture app used by the full suite.

Android 12 restricts starting foreground services from the background. Android
14 requires declared foreground-service types and corresponding permissions;
`specialUse` additionally requires an explanatory manifest property and review.
The bridge must therefore prove that its lifecycle design works without copying
the broad permanent foreground-service declaration from `hermes-android`.

Sources:

- [Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android 14 foreground-service type requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Allow restricted settings on Android](https://support.google.com/android/answer/12623953?hl=en)

### 3.2 Physical-device lanes

Emulators do not represent OEM process killing, accessibility settings,
notification listener behavior, keyboard overlays or launcher differences.
The following physical runs are release gates:

| Device class | Required OS | Gate | Purpose |
|---|---|---|---|
| Security-supported Google Pixel | Stock Android 16 / API 36 | Every release candidate | Reference implementation, latest stable target behavior, arm64 packaging. |
| Security-supported Samsung Galaxy | One UI on Android 15 or 16 | Every release candidate | Mandatory non-Pixel OEM lifecycle, settings, launcher and notification coverage. |

For a China-first pilot, add at least one current Xiaomi/HyperOS, OPPO/ColorOS
or vivo/OriginOS device before inviting external pilot users. That lane is a
pilot gate rather than the initial Phase 1 implementation gate. Exact device
model, OS build, security patch and bridge build digest must be recorded in the
test evidence; this document intentionally does not freeze a consumer model
that will quickly become obsolete.

### 3.3 Configuration dimensions

The full matrix is pairwise, not the Cartesian product. Each release must cover
every row at least once across the emulator and physical lanes.

| Dimension | Values |
|---|---|
| Navigation | Gesture navigation; three-button navigation |
| Orientation | Portrait; landscape/rotation during observation |
| Theme | Light; dark |
| Locale | `en-US`; `zh-CN` |
| Display | Default density/font; enlarged font/display size |
| Keyboard | AOSP/Gboard; one current Chinese IME on a physical device |
| Install state | Clean install; signed upgrade preserving grants; force-stop/relaunch |
| Power/network | Interactive; Doze/standby transition; Wi-Fi loss/reconnect |
| Device lock | Unlocked; lock transition during a task |

Only synthetic fixture apps, test accounts and synthetic notifications may be
used in automated evidence collection.

## 4. V0.1 capability matrix

The protocol schema remains stable for the duration of a Hermes conversation.
Availability is session/device state reported by Capability Registry, not a
reason to add or remove tool definitions after every permission change.

| Tool | API 30+ implementation baseline | Required conditions | Defined degradation |
|---|---|---|---|
| `phone.read_screen` | Accessibility window/node snapshot normalized to `PhoneState` | Accessibility enabled and service connected | Partial snapshot is marked partial with capture errors; disconnected/blocked returns typed error. |
| `phone.screenshot` | `AccessibilityService.takeScreenshot` | Accessibility enabled, capture permitted, rate limit satisfied | No MediaProjection fallback; secure/redacted/unsupported capture returns typed error. |
| `phone.tap` | Semantic node action first, accessibility gesture when policy permits | Connected Accessibility service and valid target | Missing target, rejected callback, timeout and unchanged state remain distinct. |
| `phone.long_press` | Accessibility gesture with completion callback | Same as tap | No coordinate-only retry unless the original authorized action explicitly permitted it. |
| `phone.type` | Focused editable node with `ACTION_SET_TEXT` | Focused compatible field and L2 authorization | IME/focus obstruction triggers re-observe; clipboard injection is not an implicit fallback. |
| `phone.swipe` | Accessibility gesture with completion callback | Connected service and valid geometry | Must await terminal callback; fixed sleep is not success evidence. |
| `phone.back` | Accessibility global action | Connected service | Rejected action or unchanged state is reported and verified separately. |
| `phone.home` | Accessibility global action | Connected service | Same separation of execution and verification. |
| `phone.open_app` | Exact configured package launch/allowlisted Intent | Package visibility and launchable exact target | No `QUERY_ALL_PACKAGES`; unknown/unavailable target returns `APP_NOT_FOUND`. |
| `phone.wait` | Runtime deadline plus observation transition | Active task/session | Cancellable and bounded; never sleeps past task deadline. |
| `phone.notifications` | Notification listener cursor/dedupe | User grant and listener `onListenerConnected` | Disconnect is not an empty inbox; request safe rebind and return capability error until connected. |
| `phone.current_app` | Accessibility window/event state | Connected Accessibility service | Stale data is marked with freshness; unavailable service returns typed error. |
| `phone.device_state` | Field-specific Android APIs and policy-filtered values | Per-field permission/capability | Partial response records unavailable/redacted fields; it must not fabricate defaults. |

Android 11 filters package queries. V0.1 uses declared exact packages or
purpose-specific `<queries>` entries and must not inherit upstream
`QUERY_ALL_PACKAGES`. Google Play treats broad visibility as sensitive. The
notification provider must also wait for `onListenerConnected`; an unavailable
listener is not equivalent to zero notifications.

Sources:

- [Declare package visibility needs](https://developer.android.com/training/package-visibility/declaring)
- [`NotificationListenerService` API reference](https://developer.android.com/reference/android/service/notification/NotificationListenerService)

## 5. Capability degradation contract

### 5.1 Invariants

1. Never silently replace Native API → Intent → Semantic Accessibility → Vision
   → Coordinates with a lower-trust or broader-permission mechanism.
2. Never request a broader Android permission as an automatic recovery step.
3. Never treat an empty or stale state as a successful observation.
4. Execution acceptance and post-action verification remain separate.
5. A capability loss updates Capability Registry and ends or replans affected
   work; it does not mutate the published tool schema mid-conversation.
6. Retry count, elapsed deadline and risk budget are finite. Permission/policy
   denials are not blind-retry candidates.

### 5.2 Error ownership

| Condition | Stable error/result | Recoverable behavior |
|---|---|---|
| API/service/OEM/DPM cannot provide capability | `CAPABILITY_UNAVAILABLE` | Re-observe/rebind when safe, otherwise replan or ask user. |
| User revoked grant, device is locked, or policy rejects access | `PERMISSION_DENIED` | Explain the required manual action; never navigate settings autonomously to bypass it. |
| Exact package is absent, invisible or has no launch target | `APP_NOT_FOUND` | Ask for an installed/allowed target; do not enumerate all packages. |
| Semantic target no longer exists | `NODE_NOT_FOUND` | Re-observe, alternative authorized semantic selector, then bounded recovery. |
| Android primitive reports rejection/cancel | `ACTION_REJECTED` | Re-observe; retry only if state and authorization still match. |
| Gesture/capture callback does not reach terminal state | `ACTION_TIMEOUT` | Cancel/expire attempt; never report background success later. |
| Screenshot interval is too short | `ACTION_REJECTED` with typed capture detail | One deadline-bounded wait may be selected by Recovery. |
| Action completes but observable state does not change | execution may be `SUCCEEDED`; verification `FAILED` with `STATE_UNCHANGED` | Recovery decides re-observe/replan; executor success is not task success. |
| Postcondition differs from requested goal | `VERIFICATION_FAILED` | Bounded recovery or user escalation. |
| Notification listener disconnects | `CAPABILITY_UNAVAILABLE` | Call platform rebind where valid and expose disconnected state. |
| UI hierarchy is partially inaccessible | observation marked partial plus field-level capture errors | Continue only if the plan's precondition explicitly accepts the missing fields. |

Screenshot callback failures include accessibility denial, internal errors,
rate limiting and invalid display/window. Implementations preserve this cause as
safe structured detail beneath the stable protocol error; they must not collapse
every failure to `null` or retry indefinitely.

## 6. Required test layers

| Layer | Runs | Required evidence |
|---|---|---|
| Pure unit | Every PR | Schema/policy/state diff/error mapping/recovery decisions; no Android device. |
| Python↔Kotlin contract | Every PR | Golden request/result/state fixtures, unknown fields, version negotiation, size limits. |
| Fake-capability integration | Every PR | Router → Gate → Audit → verification, deterministic callback/revoke/error injection. |
| Emulator instrumentation | PR vertical slice; nightly full | Android service lifecycle and all platform primitives on the API matrix. |
| Physical/OEM | Release candidate | Pixel and Samsung signed-build reports with manual settings/onboarding checks. |
| Chaos/lifecycle | Nightly and release | Disconnect, force-stop, reboot, delay, dialog, keyboard, rotation, app crash and network loss. |
| Security | Every PR/nightly by cost | Gate bypass, replay/expiry, artifact leakage, malicious UI text, broad package/Intent denial. |

An upstream-style unit test plus `assembleDebug` is insufficient. The audited
`hermes-android` workflow does not run emulator/instrumentation or OEM tests,
and its gesture/provider code contains paths that report after fixed delay
rather than consistently awaiting a terminal callback. HMR-113 must provide a
fixture app and observable assertions before those primitives are adapted.

Sources:

- [`hermes-android` CI workflow at the audited commit](https://github.com/raulvidis/hermes-android/blob/fbd623840bdcf1b38c835c7d2973c7b667d55da8/.github/workflows/build.yml)
- [`ActionExecutor.kt` at the audited commit](https://github.com/raulvidis/hermes-android/blob/fbd623840bdcf1b38c835c7d2973c7b667d55da8/hermes-android-bridge/app/src/main/kotlin/com/hermesandroid/bridge/executor/ActionExecutor.kt)

## 7. Required scenarios

The following scenarios are acceptance requirements, not optional exploratory
testing:

### Installation and permission lifecycle

- Clean install with no privileged grant: app remains usable and capabilities
  are accurately unavailable.
- Manual Accessibility grant, revoke while idle, and revoke during a task.
- Notification access grant, disconnect, `requestRebind`, revoke and reboot.
- Permission auto-reset/app hibernation where applicable.
- Android 13+ sideloaded debug build restricted-settings onboarding.
- Release-signed upgrade preserves compatible state without silently widening
  manifest permissions.

### Observation and action

- Compose, classic Views, WebView and a custom-drawn/semantically sparse fixture.
- Dialog, permission dialog, keyboard obstruction, rotation and enlarged font.
- Slow transition, missing element, moved element, rejected gesture and app crash.
- Screenshot success, rapid repeat/rate limit, secure-window failure and rotation.
- `open_app` for allowed installed, absent and non-launchable exact packages.
- Navigation via gesture and three-button system modes.

### Device and transport lifecycle

- Runtime/bridge disconnect during observation and during an authorized action.
- Android process death, force-stop, reboot and service reconnection.
- Wi-Fi loss/reconnect, Doze/app standby and broker unavailability.
- Android 16 local-network protection compatibility flag enabled.
- Android 17 local-network permission path in the canary lane.
- Lock during a task, locked Private Space and a managed/work-profile negative
  test; all must fail closed without attempted bypass.

Android 16 allows testing local-network protection before it becomes mandatory;
Android 17 makes the local-network permission model mandatory for apps targeting
that release. Because the broker/device transport may use the LAN, this is a
transport compatibility requirement for ADR-0005 rather than an Android UI-only
test.

Sources:

- [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Android 17 changes summary](https://developer.android.com/about/versions/17/summary)

## 8. Release evidence and privacy

Each blocking run publishes a machine-readable summary containing:

- Runtime and Android bridge commit plus build/APK digest;
- API level, OS build, OEM/model class, ABI and security patch;
- test fixture version and protocol version;
- capability snapshot before and after permission lifecycle tests;
- passed/failed/skipped test identifiers and typed failure category;
- links to redacted logs and protected test-only artifacts.

Raw screenshots, UI trees, notification bodies, contacts, clipboard contents,
location and device identifiers must not be uploaded to ordinary CI logs. Test
accounts and generated fixture content are mandatory. Artifact retention and
access follow `SECURITY.md`; coarse device metadata is enough for the public
summary.

A release is blocked by:

- any failure in API 30 or API 36 PR/release vertical slices;
- any failure in API 30, 33, 35 or 36 full release lanes;
- Pixel or Samsung release-gate failure;
- an untyped crash, false success, permission widening, raw sensitive artifact
  leak or infinite/unbounded retry;
- missing build/toolchain or system-image provenance.

API 37 canary failure creates a tracked compatibility issue but is not itself a
release blocker while the lane remains preview-only.

## 9. Maintenance policy

Review this matrix:

- quarterly;
- when a new stable Android/API or Google Play target requirement is announced;
- when `minSdk`, `targetSdk`, Android Gradle Plugin or transport changes;
- when an Accessibility, notification, foreground-service or package-visibility
  regression is found;
- before adding a new OEM to the supported list.

A new Android API enters as canary, receives a documented risk review, then is
promoted to blocking. Removing an API level from support requires a migration
notice and an explicit architecture decision. HMR-113 consumes this document to
build the emulator and physical-device harness; implementation must not quietly
weaken the matrix because a test is inconvenient.
