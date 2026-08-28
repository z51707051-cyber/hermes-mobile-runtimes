# Phase 1 Baseline Verification

- Date: 2026-08-28
- Phase: 1 / M1 Baseline (`HMR-001`)
- Downstream baseline: `a54487150a35a7a57f395f3b4cd26b2254de67d1`
- Hermes upstream baseline: `31e41eed347f62de312e59ccc822b30a635d4aba`
- hermes-android reference: `fbd623840bdcf1b38c835c7d2973c7b667d55da8`

This document records the state before Mobile Runtime product code is added. It
is a reproducibility record, not a claim that every upstream test passes in the
restricted workspace used for this run.

## Source baseline

`origin/main` contains six documentation commits on top of the selected Hermes
upstream commit. `git merge-base origin/main upstream/main` resolves to the
pinned Hermes commit, and the comparison at the time of verification was:

```text
git rev-list --left-right --count origin/main...upstream/main
6    0
```

No Hermes or Android product source was changed for this baseline. No
hermes-android source file was imported.

## Verification environment

| Component | Value |
|---|---|
| Host | Linux restricted workspace |
| Python | CPython 3.11.15 |
| uv | 0.11.33 |
| Hermes Agent | 0.20.6, editable install |
| Test runner | `scripts/run_tests.sh`, 18 workers |
| Android SDK | Not configured; `ANDROID_HOME` and `ANDROID_SDK_ROOT` unset |
| JDK | OpenJDK 17 files present; ordinary launcher cannot load `libjli.so` in this sandbox |

The Python virtual environment was created outside the Git repository, as
required by the upstream contributor instructions.

## Commands and results

### Editable development install

The repository was installed into the external Python 3.11 environment with
the upstream CI/development extras:

```bash
uv pip install --python ../venvs/hermes-dev/bin/python \
  -e '.[all,dev,anthropic,mistral,fal,modal,daytona,hindsight,parallel-web]'
```

Result: **passed**. `uv pip show` reports Hermes Agent 0.20.6 and the repository
as the editable project location.

### Lockfile

```bash
uv lock --check
```

Result: **passed**; 254 packages resolved without changing the lockfile.

### Python lint

```bash
../venvs/hermes-dev/bin/ruff check .
```

Result: **passed** (`All checks passed!`).

### Python test baseline

```bash
HERMES_PYTHON=../venvs/hermes-dev/bin/python scripts/run_tests.sh
```

Result: **not green in this environment**.

```text
3346 files
39187 tests passed
297 tests failed
381 tests skipped
3 files failed once and passed on retry
1063.4 seconds
```

The 297 failures were spread across 113 files from the unmodified Hermes
baseline. The run provides a useful inventory but is not an acceptable release
gate result. Repeated, directly observed environment correlations include:

- `/proc` is unavailable, breaking process discovery, file-descriptor and
  parent/child-liveness assertions;
- creation of Unix domain sockets and local listening sockets is denied;
- the test live-system guard rejects process-group and signal operations after
  sandbox process reparenting;
- test temporary directories reside below the repository, while credential and
  permission tests intentionally treat in-repository paths differently;
- `chown` and some mode/ownership transitions are restricted;
- Chromium and several optional system executables are absent;
- outbound provider discovery/DNS is unavailable to tests.

These conditions explain many failures, but this record deliberately does not
classify every failure as environmental. A small number of ordinary assertion
failures may be upstream defects or flakes and must be reproduced in the
standard Linux CI environment before being waived or fixed. No upstream source
or test was modified to make this restricted-workspace result appear green.

### Distribution build

```bash
uv build --out-dir ../build-artifacts/hermes-baseline
```

Result: **expected unsupported operation**. The upstream build backend raises:

```text
RuntimeError: Building wheels or sdists for hermes-agent is not supported.
Hermes is distributed via the shell installer, Docker image, or Nix.
```

For development, the supported baseline is the successful editable install.
Future release verification must exercise the actual selected distribution
path rather than treat a wheel as a required artifact.

### Android reference build

The pinned hermes-android checkout was inspected, but Gradle compile, unit test
and lint tasks were **not run** because no Android SDK is installed or
configured in this workspace. In addition, the normal Java launcher is blocked
by the sandbox dynamic-loader layout. The project metadata itself requires:

- Android Gradle Plugin 8.3;
- Gradle 8.6;
- Kotlin 1.9.22;
- JDK 17;
- compile SDK 34 and min SDK 26.

This is an environment blocker, not a passing Android baseline. Phase 1 CI must
provide a pinned JDK 17 and Android SDK before Android code is imported.

## Baseline conclusion

The Hermes control-plane source is installable in editable mode, its lockfile
is coherent, and lint passes. The full suite is not green in the current
restricted workspace and must be rerun in standard CI with the required OS
facilities. Android buildability remains unverified until the Android toolchain
is available.

This M1 result authorizes documentation and protocol-design work only. It does
not authorize importing privileged Android capabilities or relaxing future CI
gates.

## Required follow-up

1. Add a standard Linux CI job for the Hermes baseline and preserve its result.
2. Add an Android CI job pinned to JDK 17, Gradle 8.6 and SDK 34.
3. Triage any failures that reproduce outside the restricted workspace.
4. Approve ADR-0001 before starting protocol implementation.
5. Draft ADR-0002 and versioned Mobile Agent Protocol schemas in a separate PR.
