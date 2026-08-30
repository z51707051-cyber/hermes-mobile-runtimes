# HMR-103 Protocol Verification

HMR-103 is accepted only when Python and Kotlin agree on the closed V0.1
contract and the Android application still exposes no phone capability.

## Automated evidence

| Gate | Command / CI step | Expected evidence |
|---|---|---|
| Manifest integrity | `python3 scripts/mobile/generate_protocol_manifest.py --check` | Sorted file list and matching SHA-256 bundle |
| Python contract | `scripts/run_tests.sh tests/mobile/contract` | Schema meta-validation, codec, compatibility and negative fixtures |
| Python lint | `ruff check` and `ruff format --check` on mobile paths | No lint or format findings |
| Kotlin contract | `:app:testDebugUnitTest` | Shared golden, all-Tool, digest and strict-JSON tests |
| Android lint/build | `:app:lintDebug :app:assembleDebug` | Compiling protocol kernel APK |
| Dependency policy | Gradle strict verification and locks | Reviewed Moshi/Okio metadata; no unverified artifact |
| APK policy | merged-manifest and packaged-file inspection | Only `INTERNET`; no service/provider/receiver or secrets |

## Shared fixture coverage

- valid compatibility offer, execution request, authorized action and result;
- duplicate-key and unknown-field rejection;
- one valid and one invalid parameter object for every V0.1 Tool;
- bundle and Tool digest mismatch;
- downgrade and missing-required-feature rejection;
- Python/Kotlin canonical JSON and SHA-256 equality;
- state-bound action and derived recoverability rules.

The fixtures demonstrate serialization compatibility, not phone execution.
No test may satisfy HMR-103 by registering a `phone.*` handler or by reading
implementation source text.

## Dependency bootstrap

Moshi `1.15.2` is a new pinned Android dependency. CI run `33304269813`
generated the reviewed Gradle lock and SHA-256 verification metadata. The
normalized diff contains only Moshi `1.15.2`, Okio `3.7.0` and the Kotlin
JDK-compatibility artifacts selected by that graph. Moshi and Okio are
Apache-2.0 package-manager dependencies represented by the lockfiles and the
generated CycloneDX SBOM; they do not require manual entries in
`third_party/inventory.yaml`.

The bootstrap marker is removed after review. All subsequent runs must execute
with `--dependency-verification=strict`, must leave every Gradle lockfile clean,
and must fail on any unreviewed artifact.
