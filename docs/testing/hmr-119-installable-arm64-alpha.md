# HMR-119 installable ARM64 alpha gate

HMR-119 promotes the successful embedded-runtime probe into an independently
installable package for the target iQOO Z10x. The package is still an internal
fixed-key-signed alpha; it is not a Play release and it does not claim that every
desktop Hermes optional dependency works on Android.

## Automated acceptance

The `Android native wheel feasibility` workflow must:

1. cross-compile the exact ARM64 bootstrap wheels used by the real Agent;
2. stage the reviewed first-party runtime packages without generated files in
   git;
3. build once while recording dependency locks and SHA-256 verification
   metadata, then rebuild with strict dependency verification;
4. require application id `ai.hermes.mobile.runtime` and ARM64 Python native
   libraries, while rejecting x86, x86_64 and ARMv7 libraries;
5. validate the decoded APK manifest with the same fail-closed permission,
   component, backup and TLS policy as the bridge APK;
6. build a non-debuggable release APK with the protected internal key and
   reject any certificate which does not match `signing-certificate.sha256`;
7. fail the workflow when the real Hermes launcher/import probe cannot build or
   execute instead of converting that failure into a warning;
8. publish the APK, dependency metadata, packaged-file inventory and SHA-256
   digest in one review artifact.

Passwords, PINs, verification codes, QR-login challenges and biometrics remain
manual user steps. The semantic policy classifies password and authentication
code fields as L4, notification capture withholds authentication secrets, and
the embedded Agent prompt instructs Hermes to stop at the challenge and hand
control back to the user.

## Physical-device acceptance

On the unrooted iQOO Z10x running Android 15 / OriginOS 5:

1. install `Hermes-Mobile-alpha-arm64-v8a.apk` and confirm the package opens;
   the first fixed-key build cannot update the earlier debug-signed alpha, so
   remove that historical build once before installing this package;
2. configure only the model base URL, model name and API key;
3. grant notification access and Accessibility access from the buttons inside
   the app; do not grant storage, media, location, microphone or contacts;
4. run a read-only model task and confirm the foreground task notification can
   stop it while the launcher is covered;
5. select one photo or file, start an explicit WeChat share task and verify the
   grant does not survive the next task or process restart;
6. lock the screen during a model-only task and record whether OriginOS keeps
   the foreground service alive for the bounded task window.

Failures on this device gate remain release blockers even when emulator CI is
green.
