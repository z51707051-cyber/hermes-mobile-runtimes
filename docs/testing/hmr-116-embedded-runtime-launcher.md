# HMR-116 embedded runtime launcher gate

HMR-116 graduates the proven Android Hermes runtime route behind the exported
launcher without weakening the existing execution boundary.

The launcher owns one foreground task at a time. Starting a task creates a new
`HermesAndroidToolBridge`; stopping, completing or failing closes that bridge.
A stale worker completion cannot overwrite a newer or cancelled task. Prompts
and model responses remain memory-only in this stage, and API keys remain
encrypted at rest by `ModelConfigStore`. HMR-117 subsequently moved ownership
out of the Activity so configuration changes no longer cancel the task.

The bridge-only APK contains no Python engine and therefore reports the runtime
as unavailable. The complete integration APK adds the Chaquopy implementation
under the same `HermesTaskRuntime` interface. This keeps ordinary Android unit,
lint and protocol lanes small while the native-wheel lane builds the complete
artifact from hash-pinned inputs.

Acceptance requires:

1. task-state unit tests covering completion, cancellation and stale results;
2. the ordinary bridge APK to compile and fail closed without the backend;
3. the complete Android 15 APK to load the launcher runtime backend;
4. the real Agent to complete `tool_search`, `tool_describe`, `tool_call`, the
   canonical Android provider and its final model response;
5. all prior API 30/API 36 routing and security contracts to remain green.

This stage did not claim lock-screen background execution or production
WeChat/file-sharing flows. HMR-117 adds the foreground-service lifecycle;
file/photo sharing and app-specific physical-device flows remain later gates.
