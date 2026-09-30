# Actual Hermes Agent Android tool-route probe

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
instantiates `run_agent.AIAgent`. A deterministic OpenAI-compatible endpoint
then asks the real Agent to call `phone_wait`. That call crosses the Python
tool-search bridge, deferred schema loader, registration shim, a scoped and
signed Android task authorization, the production policy/router path, and the
real wait provider. Its structured result is returned to the Agent for a final
streaming model turn.

The endpoint validates bearer authentication, model, prompt, tool schema and
the returned Android execution result. This proves the first complete
Agent-tool-to-Android-result loop without an external API key or paid tokens.
The harness separately answers Hermes' non-billable local `/api/show` metadata
probe and requires exactly four `/v1/chat/completions` model requests—search,
describe, call, final response—with no SDK retry. CI stages all first-party
runtime packages reachable from the Agent, including the lazily imported
gateway session context used during tool execution.

This gate proves one non-UI capability. Accessibility gestures, file sharing
and notification summarization remain later acceptance gates.

`src/generated/python` and the ignored probe-local Kotlin staging directory are
created only in CI from the checked-out Hermes and Android bridge sources. No
generated copy is committed.
