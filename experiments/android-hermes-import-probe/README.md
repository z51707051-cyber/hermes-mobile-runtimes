# Actual Hermes Agent model-turn probe

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

CI records dependency resolution separately from runtime execution. Resolution
failure is retained as evidence and does not get hidden by looser versions or
security downgrades. If the APK builds, Android 15 instrumentation imports and
instantiates `run_agent.AIAgent`, then performs one real OpenAI-compatible
streaming turn against a loopback model endpoint hosted inside the same APK.
The endpoint validates the bearer header, model, prompt, route, and streaming
request before returning a deterministic SSE completion. This proves the
Hermes request/response path without requiring an external API key, consuming
paid model tokens, or weakening the production UI's HTTPS-only endpoint rule.

This gate does not yet claim that phone automation tools are wired to Android
accessibility or notification services. Those remain later acceptance gates.

`src/generated/python` is created only in CI from the checked-out Hermes source
and is ignored. No generated copy is committed.
