# Hermes Mobile ARM64 internal-test package

This project turns the previously isolated Android runtime probe into an
installable, fixed-key-signed ARM64 APK for the iQOO Z10x acceptance lane. It
reuses the reviewed Android bridge sources, resources and production manifest,
then embeds the real Hermes Agent with Chaquopy.

The CI build deliberately keeps the production authority boundary:

- application id `ai.hermes.mobile.runtime`;
- Android 30 minimum and Android 36 target;
- ARM64 only;
- cleartext traffic disabled and system trust anchors only;
- exactly the reviewed network, foreground-task, notification and wake-lock
  permissions;
- no storage, media, location, microphone, SMS, call, package-install or shell
  authority;
- one user-task-scoped Android bridge with L4/L5 denial.

The artifact is an internal alpha, not a store release. CI requires a protected
PKCS#12 signing key and verifies its public certificate fingerprint against
`signing-certificate.sha256`, so later builds remain upgrade-compatible.
The key itself never enters the repository or APK. `cryptography`, Pillow and psutil
are not yet part of the packaged Python set, so JWT signing, image-recovery and
process-management paths which require them remain unsupported. Model calls,
the mobile toolset, notification observation, Accessibility navigation and the
task-scoped attachment share route use the already tested runtime surface.

The repository must define the Actions secrets
`HERMES_ANDROID_KEYSTORE_BASE64`, `HERMES_ANDROID_STORE_PASSWORD`,
`HERMES_ANDROID_KEY_ALIAS`, and `HERMES_ANDROID_KEY_PASSWORD`.

GitHub Actions stages first-party Python packages and the hash-recorded ARM64
native wheels into ignored directories. It performs a strict second Gradle
release build, verifies the signing certificate, inspects the merged manifest
and APK ABI, records checksums and uploads
`Hermes-Mobile-alpha-arm64-v8a.apk` together with dependency metadata.
