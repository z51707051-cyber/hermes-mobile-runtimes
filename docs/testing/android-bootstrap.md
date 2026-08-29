# HMR-101 Android Bootstrap Verification

> Phase: 1 / HMR-101  
> Status: implementation review  
> Last updated: 2026-08-29

## Scope

HMR-101 establishes the Android build boundary at
`apps/mobile-bridge-android/`. It does not implement Mobile Agent Protocol,
Accessibility, screenshots, notifications, Intents, device enrollment or any
other phone capability.

The acceptance target is a reproducible debug APK whose final merged manifest
has zero Android permissions and no executable component other than its
launcher activity.

## Toolchain baseline

| Item | Required value |
|---|---:|
| JDK | 17 |
| Gradle | 9.5.0, wrapper distribution SHA-256 pinned |
| Android Gradle Plugin | 9.3.2 |
| Android build tools | 36.0.0 |
| `compileSdk` / `targetSdk` | 36 |
| `minSdk` | 30 |

This is the stable API 36 build lane required by the compatibility matrix.
API 37 remains a non-blocking future canary and is not part of this bootstrap.

## Required checks

The `Mobile Android bootstrap` workflow performs:

1. wrapper JAR hash verification and wrapper distribution hash verification;
2. exact Android SDK package installation;
3. Kotlin unit tests;
4. Android lint with warnings treated as errors;
5. debug APK assembly;
6. source and final merged manifest policy checks;
7. APK packaged-file inventory and secret-like filename rejection;
8. strict Gradle dependency verification and dependency locking;
9. direct APK and build-wide CycloneDX JSON/XML SBOM generation, with the APK
   SBOM restricted to `debugRuntimeClasspath` and validated as an application;
10. upload of APK, checksum, manifest, file inventory and SBOM.

Dependency verification metadata and lockfiles are generated once by CI,
reviewed and committed, then enforced. The workflow deliberately fails its
bootstrap pass after uploading those files so unreviewed generated trust data
cannot silently become authoritative.

## Manifest acceptance policy

- No `<uses-permission>` or `<uses-permission-sdk-23>`.
- `android:allowBackup="false"`.
- `android:usesCleartextTraffic="false"`.
- Exactly one activity, the bootstrap `MainActivity`.
- Only that launcher activity is exported.
- No service, receiver, provider or activity alias.
- No application- or activity-level Android permission.

CI checks the built APK manifest rather than trusting source text alone. This
catches components or permissions contributed by dependencies and manifest
merging.

## Local environment result

The managed Phase 1 workspace does not provide a functional JDK or Android SDK,
so a local APK build is not claimed. Local checks cover the manifest policy,
test behavior, repository diff and workflow syntax. GitHub-hosted CI is the
authoritative HMR-101 build/lint/unit/SBOM lane. Its run and artifact links are
recorded in the Pull Request before merge.
