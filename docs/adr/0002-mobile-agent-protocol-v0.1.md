# ADR-0002: Mobile Agent Protocol V0.1

- Status: Accepted
- Date: 2026-08-28
- Decision owners: Hermes Mobile Runtime maintainers
- Phase: 1 / HMR-004

## Context

Hermes must be able to plan mobile work without depending on Android
`AccessibilityNodeInfo`, an HTTP endpoint from an upstream prototype, or a
particular model provider's function-call format. The Android bridge must also
be able to reject stale, duplicated, unauthenticated or incompatible requests
before a capability runs.

The protocol therefore has to serve three distinct boundaries:

1. a model-provider-neutral Hermes tool adapter;
2. a versioned Runtime request/result contract;
3. a protected Runtime-to-device command carrying authorization that is never
   exposed to the model.

The protocol is not the transport. Enrollment, mutual authentication, key
rotation and wire framing are decided by ADR-0005. Recovery policy is decided
by ADR-0006.

## Decision

### 1. Contract shape and encoding

Mobile Agent Protocol V0.1 is a transport-neutral family of UTF-8 JSON
messages. Normative schemas use
[JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12/json-schema-core).
Python and Kotlin implementations are generated or validated from the same
checked-in schemas; neither language implementation is normative by itself.

Schemas use closed objects for requests, authorizations and security-critical
records. Optional evolution fields live under a namespaced `extensions`
object. An unknown extension cannot change authorization, target selection,
idempotency or verification semantics.

JSON inputs with duplicate keys, invalid UTF-8, non-finite numbers or values
outside their schema are rejected. Tool-specific normalization may canonicalize
package names, enums and coordinates, but free-form user text is not Unicode
normalized or otherwise rewritten.

### 2. Protocol layers

| Layer | Message | Sender → receiver | Authorization content |
|---|---|---|---|
| Hermes adapter | `ModelToolInvocation` | Model provider → Hermes | None |
| Runtime API | `ToolExecutionRequest` | Hermes → Runtime broker | None; caller cannot assert a level or approval |
| Protected device API | `AuthorizedAction` | Runtime broker → Android PEP | Broker-issued execution authorization |
| Runtime API | `ToolExecutionResult` | Runtime broker → Hermes | Decision reference and redacted evidence only |
| Observation | `PhoneState` / `ArtifactRef` | Android → Runtime | Capture policy and sensitivity metadata |
| Event ingestion | `MobileEvent` | Android → Runtime | Source authentication metadata |

Hermes, Skills, plugins and MCP servers may create only
`ToolExecutionRequest`. They cannot construct `AuthorizedAction`, set an
effective risk level, mark a confirmation as complete or obtain a device
session credential.

### 3. Version negotiation

The protocol version is a three-component string, initially `0.1.0`:

- a major change is incompatible;
- a minor change may add optional fields, tools or enum values negotiated as
  capabilities;
- a patch change clarifies or fixes a schema without changing valid message
  meaning.

Before normal traffic, peers exchange supported minimum/maximum versions,
schema bundle digest, tool/schema digests and feature flags. The negotiated
version and schema digest are bound to the authenticated session.

Rules:

- no common major/minor version returns `PROTOCOL_INCOMPATIBLE`;
- a peer never silently selects a lower version than configured minimum;
- unknown required feature or security-critical enum returns
  `PROTOCOL_INCOMPATIBLE`;
- unknown optional response fields may be retained or ignored;
- unknown request fields are rejected unless contained in a negotiated,
  namespaced extension;
- tool schemas remain fixed for the life of a Hermes conversation. A device
  disconnect produces a typed availability error rather than mutating the
  past tool schema and breaking prompt caching.

### 4. Canonical tools and model-facing aliases

The canonical operation names on the Runtime wire are the required dot names.
The Hermes named toolset is `mobile`. Provider-facing function names use a
portable underscore alias because providers do not share one universal naming
grammar.

| Canonical operation | Hermes function alias | Baseline effect | V0.1 parameter contract |
|---|---|---:|---|
| `phone.read_screen` | `phone_read_screen` | L0 | Optional node/text limits and active-window scope |
| `phone.screenshot` | `phone_screenshot` | L0 | Optional display and bounded crop; no file path supplied by caller |
| `phone.tap` | `phone_tap` | L1+ | Fresh semantic target or state-bound coordinates |
| `phone.long_press` | `phone_long_press` | L1+ | Target plus bounded `duration_ms` |
| `phone.type` | `phone_type` | L2+ | Exact `text`, optional focused target and replace/append mode |
| `phone.swipe` | `phone_swipe` | L1 | State-bound start/end points and bounded duration |
| `phone.back` | `phone_back` | L1 | Empty object |
| `phone.home` | `phone_home` | L1 | Empty object |
| `phone.open_app` | `phone_open_app` | L1+ | Exact Android package; resolver output is not trusted implicitly |
| `phone.wait` | `phone_wait` | L0 | Bounded timeout and optional observable condition |
| `phone.notifications` | `phone_notifications` | L0 | Cursor, bounded limit and optional source filter |
| `phone.current_app` | `phone_current_app` | L0 | Empty object |
| `phone.device_state` | `phone_device_state` | L0 | Allowlisted field projection |

`+` means semantic resolution may raise the effective level. For example,
`phone.tap` on **Send** is L3 and on **Delete** is at least L4. The alias map is
static and one-to-one; aliases never appear on the Android wire or in Mobile
Skill definitions.

Coordinates are a fallback. A target has a `state_id` and either a normalized
semantic node reference or normalized coordinates. It must not contain raw
Android objects. State validity and target generation are decided in ADR-0004.

### 5. ToolExecutionRequest

Every request contains:

| Field | Requirement |
|---|---|
| `protocol_version` | Negotiated version |
| `request_id` | Globally unique opaque request identifier |
| `task_id` | Stable user-task correlation identifier |
| `span_id` | Current action/audit span identifier |
| `device_id` | Enrolled target device identifier |
| `tool` | One canonical `phone.*` operation |
| `parameters` | Tool-schema-valid parameters |
| `state_precondition` | Required for state-changing UI targets; absent only where schema permits |
| `verification` | Requested observable postcondition, never a claim of success |
| `idempotency_key` | Unique key for this logical operation |
| `attempt` | Positive attempt number within the same logical operation |
| `requested_at` | UTC RFC 3339 timestamp |
| `deadline` | UTC RFC 3339 deadline; server may apply a shorter limit |

`session_id`, `turn_id`, `api_request_id` and provider `tool_call_id` remain
Hermes correlation values. The adapter may link them in local audit context,
but it does not parse or reuse them as `request_id`, authorization or
idempotency keys.

The caller does not send `permission_level`, `policy_decision`,
`confirmation=true`, `authorization` or retry permission. Presence of such an
unknown field fails schema validation.

### 6. AuthorizedAction

After validation, observation and policy evaluation, the protected broker
normalizes the request and creates an `AuthorizedAction` for the Android PEP.
It contains the request fields plus:

- `action_digest`;
- effective semantic target and risk;
- `policy_decision_id`;
- short-lived, single-purpose `execution_authorization`;
- broker/device session binding;
- expiry, nonce and replay metadata.

The action digest is computed over the security-relevant normalized fields
using [RFC 8785 JSON Canonicalization Scheme](https://www.rfc-editor.org/rfc/rfc8785)
before hashing. The protected input includes at least protocol major/minor,
request and device ids, canonical tool, exact parameters, stable target/state
precondition, effective risk, idempotency key and request deadline. Attempt
number and authorization expiry are signed authorization metadata but are not
part of the logical action digest, so the broker can renew authorization for
an otherwise identical approved retry. Implementations must use the checked-in
digest fixture; hand-built serialization is prohibited.

The broker sends `AuthorizedAction` directly to the enrolled device. It is not
returned through a model tool result or stored in a Skill trace.

### 7. ToolExecutionResult

Each terminal result contains at least the product-required fields:

| Required field | Meaning |
|---|---|
| `request_id` | Exact originating request |
| `tool` | Canonical operation |
| `parameters` | Validated parameters or schema-shaped redacted projection |
| `execution_status` | Low-level executor status, not task success |
| `before_state` | `PhoneStateRef` and safe transition summary |
| `after_state` | `PhoneStateRef` and safe transition summary |
| `duration` | Monotonic elapsed duration as integer milliseconds |
| `error` | Typed safe error object or `null` |
| `recoverable` | Compatibility summary derived from retry disposition |
| `timestamp` | UTC RFC 3339 completion timestamp |

It also contains `protocol_version`, `task_id`, `span_id`, `device_id`,
`attempt`, `idempotency_key`, `parameter_digest`, `permission_decision_id`,
`verification`, `artifacts` and `redactions`.

`execution_status` is one of:

- `NOT_STARTED`
- `AWAITING_CONFIRMATION`
- `SUCCEEDED`
- `FAILED`
- `DENIED`
- `CANCELLED`
- `TIMED_OUT`
- `UNKNOWN_OUTCOME`

`SUCCEEDED` means only that the capability reported completion. It does not
mean the screen changed as expected or that the user's task succeeded.

### 8. Verification and task result separation

`VerificationResult.status` is one of `NOT_APPLICABLE`, `PENDING`, `PASSED`,
`FAILED` or `INCONCLUSIVE`. It records the expected condition, observed state
ids, evaluator/version, evidence references and a safe explanation.

`TaskResult` belongs to the Runtime/planner orchestration layer and is not an
Android capability response. It may be `COMPLETED`, `INCOMPLETE`, `DENIED`,
`FAILED` or `ESCALATED`.

The following implication is invalid and must be rejected in tests:

```text
ExecutionResult.SUCCEEDED => TaskResult.COMPLETED
```

### 9. State and artifact references

`before_state` and `after_state` never inline a raw screenshot, complete UI
tree or notification body. A `PhoneStateRef` carries state id, capture time,
freshness, device id, foreground summary, transition summary and protected
artifact references.

`ArtifactRef` uses an opaque id plus media type, size, keyed digest,
sensitivity, redaction status, retention class and expiry. It never contains a
filesystem path, bearer token, content-derived URL or device credential.
Artifact retrieval is a separate authorized and audited operation.

PhoneState consistency, skew limits and storage are deferred to ADR-0004.

### 10. Error ownership and retry advice

`error` contains stable `code`, `category`, `owner`, safe `message`,
`retry_disposition`, optional `details` and `cause_request_id`. The initial
codes in `ARCHITECTURE.md` remain reserved. `retry_disposition` is one of:

- `NEVER`
- `REOBSERVE`
- `RETRY_SAME_ACTION`
- `REPLAN`
- `ASK_USER`

`recoverable` is true only for `REOBSERVE`, `RETRY_SAME_ACTION` or `REPLAN`; it
does not authorize the retry. The Recovery Engine must still apply ADR-0006,
the original deadline, risk budget and Permission Gate.

Error owners are `HERMES_ADAPTER`, `RUNTIME`, `POLICY`, `TRANSPORT`,
`ANDROID_PEP`, `CAPABILITY`, `OBSERVER` or `VERIFIER`. Unknown error codes are
treated as non-recoverable until negotiated.

### 11. Idempotency and ambiguous completion

Every operation, including reads, has an idempotency key. The Android PEP
maintains a bounded replay/result cache keyed by enrolled device identity and
idempotency key.

- Same key and same action digest returns the recorded terminal result without
  executing again.
- Same key and different digest returns `ACTION_REJECTED` and emits a security
  audit event.
- A new attempt for the same logical action keeps the request id and
  idempotency key while incrementing `attempt`; altered parameters create a
  new request and require a new policy decision.
- A transport loss after a mutation produces `UNKNOWN_OUTCOME` until the
  result cache and a fresh observation resolve it.
- Communication, deletion, purchase, install and similar irreversible effects
  are never automatically repeated after `UNKNOWN_OUTCOME`.

The bounded cache duration and retry budget are set by ADR-0006 and cannot be
shorter than the maximum request/transport replay window selected by
ADR-0005.

### 12. Time and ordering

Wall-clock fields use UTC
[RFC 3339](https://www.rfc-editor.org/info/rfc3339/) timestamps. Durations use a
monotonic clock and are serialized in the `duration` field as integer
milliseconds. Wall-clock order alone is not authorization evidence. Nonces,
session sequence numbers, deadlines and the replay cache provide ordering and
freshness.

### 13. Event envelope

V0.1 reserves the following base event without enabling proactive execution:

```text
MobileEvent {
  protocol_version, id, type, source, device_id, timestamp,
  cursor, payload, sensitivity, deduplication_key, redactions
}
```

The payload is selected by an event-specific schema. An event can start only a
new gated decision; it cannot carry an execution authorization or inherit the
permission of an earlier task.

### 14. Schema and fixture layout

HMR-103 implements this decision using:

```text
hermes_mobile/protocol/schemas/v0.1/
  common/
  requests/
  results/
  tools/
  events/
apps/mobile-bridge-android/.../bridge/protocol/
tests/mobile/contract/fixtures/v0.1/{valid,invalid,canonical}/
```

Each schema has a stable `$id`. The schema bundle manifest contains every file
digest. Golden fixtures include Python encode → Kotlin decode, Kotlin encode →
Python decode, unknown-field rejection, version downgrade, parameter mutation,
canonical digest and duplicate/replay cases.

### 14.1 HMR-103 implementation profile

- `manifest.json` is generated from sorted schema paths and per-file SHA-256
  digests. Both languages recompute it before accepting the bundle.
- Python uses Draft 2020-12 validation with a fully offline schema registry.
  Android uses a closed Kotlin validator and verifies the same normative
  bundle; it does not fetch schemas at runtime.
- Protocol documents are at most 1 MiB, 64 levels deep and 4,096 items per
  container. Duplicate keys, invalid UTF-8, non-finite values and integers
  outside the interoperable ±(2^53−1) range are rejected.
- The V0.1 authorization digest domain forbids floating-point values. Within
  this deliberately smaller domain, Python and Kotlin canonicalization is
  compatible with RFC 8785 and locked by shared fixtures.
- The exact logical digest fields are `protocol_line`, `request_id`, `task_id`,
  `device_id`, `tool`, `parameters`, `state_precondition`, `verification`,
  `idempotency_key`, `deadline`, `effective_target` and `effective_risk`.
  Replay, expiry, attempt and signature metadata remain separately signed.
- Compatibility ranges are limited to one major/minor line. The selected patch
  is the highest common version above the configured minimum; bundle, Tool
  digest or required-feature mismatches fail closed.

## Rejected alternatives

### Expose the upstream Android HTTP command API directly

Rejected because it couples Hermes to transport and Android implementation,
exposes a broad privileged surface and has no versioned authorization/result
contract.

### Use model function names as protocol operation names

Rejected because provider naming rules are an adapter concern. Changing model
provider must not rename Skills, audit records or Android capabilities.

### Put raw screenshots and UI trees in every result

Rejected because it increases prompt size, logging risk and retention without
providing a stable state identity.

### Use one `success` boolean

Rejected because capability acceptance, observed postcondition and user-task
success are different facts.

### Treat retries as transport behavior

Rejected because duplicate UI mutations can send, delete or purchase twice.
Retry is an explicit policy/recovery decision bound to idempotency and fresh
state.

## Consequences

### Positive

- Hermes, Runtime and Android can evolve independently.
- Provider-specific function naming does not leak into Skills or device APIs.
- Duplicate and ambiguous actions are visible and bounded.
- Sensitive artifacts remain outside normal tool JSON.
- Python/Kotlin compatibility can be proven with shared fixtures.

### Costs

- Schema generation, manifesting and golden fixtures are release requirements.
- Read operations also carry correlation and idempotency metadata.
- Capability changes require explicit negotiation instead of ad hoc fields.
- State, transport, authorization and recovery require follow-up ADRs.

## Compliance checks

Before the first Android action is enabled:

1. All 13 tools have closed request schemas and valid/invalid fixtures.
2. Python and Kotlin validate the same schema bundle digest.
3. The canonical alias map is one-to-one and the `mobile` toolset is
   session-gated.
4. A disconnected device does not mutate an active conversation's tool schema.
5. Security fields sent by a model request are rejected.
6. Execution success cannot be serialized as task success without a separate
   verification result.
7. Duplicate, changed-digest and ambiguous-outcome fixtures pass.

## Follow-up decisions

- ADR-0003: Permission Gate enforcement and confirmation binding.
- ADR-0004: PhoneState consistency and artifact storage.
- ADR-0005: Device identity, transport and key rotation.
- ADR-0006: Error taxonomy and bounded recovery policy.
