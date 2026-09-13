# Bundled Python feasibility probe

This separate APK tests CPython 3.11 inside Android with Chaquopy 17.0.0 and the
repository's AGP 9.3.2 / Gradle 9.5.0 toolchain. It does **not** start Hermes,
accept API keys, access the network, or control phone apps. It installs beside
the existing developer preview, with application ID `ai.hermes.runtime.probe`.

The instrumentation executes real Python, persists and reads a Unicode SQLite
record in app cache, verifies TLS defaults, and reports interpreter/architecture.
CI executes this on Android 15 x86_64; ARM64 is packaged but still requires
physical-device execution. Build success alone is not runtime evidence.

Run with JDK 17, Android SDK 36 and Python 3.11 on the build machine:

```sh
apps/mobile-bridge-android/gradlew -p experiments/android-python-probe \
  --write-locks --write-verification-metadata sha256 \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

This initial experiment generates dependency locks/checksums as CI artifacts for
review. It must not be promoted to production without checked-in reviewed
metadata and strict verification. No production verification configuration is
changed. The next gate is packaging the actual pinned Hermes dependencies and
importing the actual Agent, not replacing it with a mock.
