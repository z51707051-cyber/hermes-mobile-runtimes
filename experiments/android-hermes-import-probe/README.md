# Actual Hermes import probe

This experiment packages the repository's real `run_agent` entry point with
the core dependency constraints from `pyproject.toml`. It uses Python 3.13 so
Android wheels can follow the current cibuildwheel route recommended by
Chaquopy. The test is staged after the proven CPython-only probe.

CI records dependency resolution separately from runtime import. Resolution
failure is retained as evidence and does not get hidden by looser versions or
security downgrades. If the APK builds, Android 15 instrumentation imports
`run_agent.AIAgent`. A successful import still does not claim a model turn or
phone workflow; those are later gates.

`src/generated/python` is created only in CI from the checked-out Hermes source
and is ignored. No generated copy is committed.
