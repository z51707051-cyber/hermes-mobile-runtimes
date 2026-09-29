# Actual Hermes import probe

This experiment packages the repository's real `run_agent` entry point with
the core dependency constraints from `pyproject.toml`. It uses Python 3.13 so
Android wheels can follow the current cibuildwheel route recommended by
Chaquopy. The test is staged after the proven CPython-only probe.

The first import gate uses the same versions but excludes three packages which
do not import on the Agent startup path: `cryptography`, `psutil`, and `Pillow`.
It also omits the `crypto` and `standard` extras which pull those or additional
native extensions. This is an explicitly incomplete bootstrap set: affected
JWT signing, process management, image recovery, and standard Uvicorn extras
remain acceptance blockers and must receive Android implementations or exact
wheels before a complete-runtime claim.

CI records dependency resolution separately from runtime import. Resolution
failure is retained as evidence and does not get hidden by looser versions or
security downgrades. If the APK builds, Android 15 instrumentation imports
`run_agent.AIAgent`. A successful import still does not claim a model turn or
phone workflow; those are later gates.

`src/generated/python` is created only in CI from the checked-out Hermes source
and is ignored. No generated copy is committed.
