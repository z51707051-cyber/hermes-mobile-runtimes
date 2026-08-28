# Mobile Agent Protocol

The normative V0.1 decisions are in
[ADR-0002](../adr/0002-mobile-agent-protocol-v0.1.md). Permission decisions and
execution authorization are defined in
[ADR-0003](../adr/0003-permission-gate-enforcement.md).

No protocol implementation or Android product code exists yet. HMR-103 will
add the JSON Schema bundle, Python/Kotlin codecs and bidirectional golden
fixtures only after both ADRs are accepted.

## Stable boundary

- Runtime operations use canonical dot names such as `phone.current_app`.
- Hermes exposes portable model aliases such as `phone_current_app` through a
  named, session-gated `mobile` toolset.
- Hermes can submit an unprivileged `ToolExecutionRequest`; only the protected
  broker can create an `AuthorizedAction`.
- Android returns executor facts. Runtime verification and task completion are
  separate results.
- Raw screenshots, UI trees and notification bodies are protected artifacts,
  not ordinary result fields.

## V0.1 operation set

```text
phone.read_screen       phone.screenshot       phone.tap
phone.long_press        phone.type             phone.swipe
phone.back              phone.home             phone.open_app
phone.wait              phone.notifications    phone.current_app
phone.device_state
```

Any additional operation requires a versioned schema, capability negotiation,
risk classification and contract fixtures. Shizuku, Shell, arbitrary Intent,
APK installation, payments and transfers are excluded from V0.1.
