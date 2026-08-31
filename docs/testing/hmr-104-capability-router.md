# HMR-104 Capability Registry and Tool Router Verification

HMR-104 establishes the only authorized dispatch path without adding a real
phone capability, transport listener or model-facing Tool registration.

## Routing invariants

| Boundary | Accepted input | Required next step | Fail-closed behavior |
|---|---|---|---|
| Runtime Router | Serialized, codec-valid `ToolExecutionRequest` | Capability lookup, then protected Policy Broker | Unknown/unavailable Tool stops before policy or device calls |
| Policy Broker response | Bound `AuthorizedAction` or terminal policy result | Risk-floor check or safe return | Mutated request, missing decision or executable fake result is rejected |
| Android Router | Serialized, codec-valid `AuthorizedAction` | Android PEP evaluation | Ordinary Tool request never reaches PEP/provider |
| Android provider | Router-wrapped `AuthorizedAction` | One narrow capability implementation | Missing/duplicate/unknown provider fails closed |
| Provider result | Codec-valid `ToolExecutionResult` | Correlation/digest/decision binding | Any mismatch is `ACTION_MISMATCH`/`ACTION_REJECTED` |

The fixed catalog contains the 13 V0.1 protocol Tools. Device reports may
change availability, but cannot add a Tool, lower its minimum L0–L5 risk or
mutate the schema exposed during an active conversation.

## Automated evidence

Python tests prove:

- the Runtime catalog exactly matches the normative Tool schema bundle;
- device capability generations are monotonic and unknown reports fail;
- an unavailable capability reaches neither Policy Broker nor Android;
- planner-supplied `AuthorizedAction` is rejected;
- only an exactly bound broker authorization reaches the device transport;
- policy denial/confirmation results never reach the device;
- request mutation, risk under-classification and result-binding mismatch fail.

Kotlin tests prove:

- the Android catalog exactly matches `ProtocolCodec`;
- duplicate and unknown providers cannot register;
- `ToolExecutionRequest` reaches neither PEP nor provider;
- PEP denial happens before provider resolution;
- missing providers fail closed after a PEP allow decision;
- an authorized provider runs exactly once;
- risk-floor and result/decision mismatches fail before a result is returned.

## Commands

```bash
scripts/run_tests.sh -j 4 tests/mobile/contract \
  tests/mobile/unit/test_runtime_router.py -q

cd apps/mobile-bridge-android
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug cyclonedxBom
```

CI additionally applies the existing APK manifest/content and SBOM checks.
At the HMR-104 checkpoint the APK had only `INTERNET`, no
service/provider/receiver and no code path that read or mutated Android
application state. HMR-105 deliberately supersedes that component inventory
with one system-protected, read-only Accessibility service.

## Deferred after HMR-104

- real protected-broker IPC and device transport wiring;
- signature/session/replay/audit enforcement in a production PEP;
- model-facing Tool registration through that protected client path;
- full PhoneState capture, verification and append-only audit persistence.

HMR-105 delivers the first `phone.current_app` Provider and a redacted Audit
interface, but intentionally does not claim the remaining production wiring.
