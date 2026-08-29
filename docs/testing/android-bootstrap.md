# HMR-101 Android Bootstrap Verification

> Phase: 1 / HMR-101
> Status: review evidence complete
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

## CI evidence

The strict dependency-verification run for commit
`b48e874642aadeac2db74f8ee3a8482de4e20ccf` passed all Android steps:

- [GitHub Actions run 33233353447](https://github.com/z51707051-cyber/hermes-mobile-runtimes/actions/runs/33233353447)
- evidence artifact ID: `9709191348`
- evidence ZIP SHA-256: `a0350ec5923b5a9eb303e2c20c01207fda974bf40fe9c7763ccf6abc000016e9`
- debug APK SHA-256: `561d98316ffef3c3a81b0eabad68ee12085145aac9b761746a633e740fc5654c`
- debug APK size: 2,519,625 bytes

The decoded APK manifest contains `minSdk=30`, `compileSdk=36`, `targetSdk=36`,
zero Android permissions and only the exported launcher activity. The archive
integrity check passed. The direct APK SBOM is CycloneDX 1.6, identifies the
artifact as an application, declares the project MIT license and contains two
runtime dependencies (`kotlin-stdlib` and JetBrains `annotations`), both with
license metadata. A separate build-wide SBOM is included for build provenance.

This evidence predates only the CI assertion that the already-present MIT SBOM
metadata must remain present; the Pull Request's latest run is the merge gate.

## Local environment result

The managed Phase 1 workspace does not provide a functional JDK or Android SDK,
so a local APK build is not claimed. Local checks cover the manifest policy,
test behavior, repository diff and workflow syntax. GitHub-hosted CI is the
authoritative HMR-101 build/lint/unit/SBOM lane. Its run and artifact links are
recorded in the Pull Request before merge.
