# HMR-105 `phone.current_app` Verification

HMR-105 is the first read-only capability consumer of the HMR-101–104
foundation. It does not add a raw transport route or broaden the Tool catalog.

The provider-to-Router contract is now complete, but the production broker IPC
and authenticated device transport are still intentionally absent. Therefore
the portable `phone_current_app` model alias is not registered yet: exposing a
model Tool before the protected client path exists would be dead code or would
create a bypass. The production PEP remains deny-all until that path supplies
the reviewed authorization verifier.

## Protected path

```text
ToolExecutionRequest
→ Runtime Capability Registry
→ protected Policy Broker decision
→ Runtime Audit precommit
→ AuthorizedAction
→ Android authorization PEP
→ live current-app capability check
→ CurrentAppProvider
→ schema-valid ToolExecutionResult
→ Runtime result/state Audit correlation
```

## Android observation boundary

The API 30+ baseline follows the accepted compatibility matrix and uses a
minimal `AccessibilityService` window-event observer. Android alone binds the
exported service through `android.permission.BIND_ACCESSIBILITY_SERVICE`.

The checked configuration is intentionally narrower than later Phase 1 work:

- only `typeWindowStateChanged` events;
- `canRetrieveWindowContent=false`;
- `canPerformGestures=false`;
- no node, text, bounds, screenshot, gesture, notification, shell or Intent
  access;
- no retained `AccessibilityEvent` or Android UI object.

The tracker stores a generated state id, package, class name and capture clocks.
V0.1 sends only the foreground package in `PhoneStateRef`; ADR-0004/HMR-106
owns the full activity/state model. Disconnected, absent or older-than-five-
seconds data is unavailable. A disconnect between PEP and Provider returns a
schema-valid `CAPABILITY_UNAVAILABLE` result.

## Audit boundary

Runtime requires an `ExecutionAuditSink`. Before device dispatch it records
request/task/span/device correlation, parameter digest, policy decision,
action digest and L0 risk. After a terminal result it records execution status
and before/after state ids. Raw parameters, screen data and activity text are
not part of this record. HMR-107 will implement durable append-only storage,
integrity chaining, retention and export.

Audit precommit failure returns `AUDIT_UNAVAILABLE` and prevents device
dispatch. HMR-105 is stricter than the future optional L0 gap policy because no
trusted durable gap writer exists yet.

## Automated evidence

Python behavior tests prove:

- planner requests still cannot submit `AuthorizedAction`;
- capability and policy failures reach neither Android nor Audit incorrectly;
- Audit precommit happens before device execution;
- result Audit records bind before/after state ids and parameter digest.

Kotlin behavior tests prove:

- current-app state requires a connected, fresh window event;
- an authorized L0 action reaches the real Provider once;
- Provider result binds request, decision, parameter digest and state;
- revoked/stale capability fails at PEP before Provider invocation;
- a disconnect race produces a typed schema-valid failure;
- authorization denial occurs before live capability inspection.

The manifest policy test additionally rejects unprotected services, unexpected
components, UI-tree retrieval, gestures and any `uses-permission` beyond
`INTERNET`.

## Commands

```bash
scripts/run_tests.sh -j 4 tests/mobile/contract \
  tests/mobile/unit/test_runtime_router.py \
  tests/mobile/unit/test_android_manifest_policy.py -q

cd apps/mobile-bridge-android
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug cyclonedxBom
```

The Android build and built-APK policy/SBOM inspection remain mandatory CI
evidence. A real enable/disable lifecycle run on API 30 and API 36 remains an
emulator gate; HMR-113 owns the reusable deterministic emulator harness.
