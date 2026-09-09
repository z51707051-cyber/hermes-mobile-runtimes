# ADR-0010: State-bound Navigation Actions

- Status: Accepted
- Date: 2026-09-09
- Decision owners: Hermes Mobile Runtime maintainers
- Phase: 1 / HMR-110

## Context

HMR-105–109 established the protected Router/PEP, coherent PhoneState,
semantic tree and screenshot artifacts. The Runtime now needs its first
mutations, but exposing raw Accessibility actions or accepting an old node
number would let stale state, UI drift or an under-classified **Send/Delete**
control bypass the Permission Gate.

Android also reports only whether an action was accepted/completed. That is
not evidence that the requested screen or user goal was reached.

## Decision

### 1. One closed navigation source behind Router and PEP

Seven existing V0.1 tools receive providers: `tap`, `long_press`, `type`,
`swipe`, `back`, `home` and `open_app`. They share one weak reference to the
system-bound Accessibility service. There is no reflection, arbitrary action
id, arbitrary Intent, shell, Shizuku, Binder endpoint or model-facing Android
object.

The Accessibility configuration now sets `canPerformGestures=true` for
bounded long-press and swipe paths. It continues to request only `INTERNET`,
listen only for `typeWindowStateChanged`, use only `flagReportViewIds` and
expose only the system-protected Accessibility service. This intentionally
supersedes ADR-0008/0009's read-only gesture setting.

Android 11+ package visibility is declared as exactly one MAIN/LAUNCHER intent
query so `open_app` can resolve the signed package. The app does not request
`QUERY_ALL_PACKAGES`, enumerate installed apps or accept caller-defined Intent
fields.

### 2. Exact state and target binding

Python and Kotlin codecs require every tap/long-press/type target and both
swipe points to use the same state id as `state_precondition`. Immediately
before provider dispatch, Android PEP requires:

- a connected capability and fresh `COMPLETE` PhoneState;
- exact state id and optional foreground package;
- an `effective_target` identical to the signed tap/long/type target;
- no effective target for global/open-app/swipe actions; and
- effective risk at least the freshly resolved device-side risk.

The provider repeats the fresh state and active-root identity checks before
mutation. A mismatch fails without execution.

### 3. Ephemeral semantic target index

A complete `phone.read_screen` capture registers at most 500 primitive target
descriptors for five minutes and at most 16 generations. It never retains an
`AccessibilityNodeInfo`. Raw text is used only while computing a keyed
HMAC-SHA256 signature and risk floor; the registry stores the signature and
risk, not the text.

Before policy allow and again before execution, Android traverses the active
root under the same node/depth bounds, reconstructs the requested capture-local
node and compares its package, window and keyed descriptor signature. Bounds,
class/resource identity, protected text, enabled/visible/editable/clickable
properties and password state are bound. Drift returns a typed re-observe
failure.

Risk/signature input uses its own per-field bound rather than the UI artifact's
global text budget. Exhausting result text therefore cannot erase the semantics
of a later Send/Delete/Pay target and downgrade its device-side risk.

### 4. Conservative semantic risk

Device-side classification raises common save/submit/confirm controls to L2,
communication controls such as send/post/call to L3, and
delete/pay/purchase/transfer/install controls to L4. Password entry is at
least L3. Blank semantic controls are L3 rather than silently assumed safe.

L4/L5 navigation remains blocked because device-authenticated confirmation is
not implemented. Coordinate tap/long-press cannot be semantically re-resolved
and therefore resolves to L4 and fails closed. Vertical navigation swipes are
L1; other swipe directions require L2. The classifier is defense in depth,
not a substitute for the protected broker's policy and confirmation.

### 5. Bounded execution adapters

- Tap invokes `ACTION_CLICK` on the re-resolved enabled visible clickable node.
- Long press dispatches one center-point gesture for the schema-bounded
  duration.
- Type requires an explicit re-resolved semantic target and invokes
  `ACTION_SET_TEXT`. Targetless current-focus input is rejected because focus
  can change between policy and execution without changing the window
  generation. Replace is exact; append rechecks the live existing text,
  rejects password or truncated sources, and bounds the resulting text.
- Swipe dispatches one schema-bounded path.
- Back/Home use only their fixed Android global actions.
- Open app resolves an installed launcher component for the exact signed
  package, then reconstructs an explicit MAIN/LAUNCHER Intent without extras,
  URI or caller-controlled flags.

Gesture completion has a finite duration-derived deadline. This provider does
not retry an action.

### 6. Observe and verify after every accepted mutation

After Android accepts/completes an action, the provider waits 200 ms and polls
for a coherent semantic observation for at most 1.5 seconds. It evaluates the
signed verification request for state change, foreground package or protected
text presence/absence. Raw observed text never enters the result.

The UI hierarchy fingerprint excludes capture time; observation time remains
in PhoneState. Therefore `STATE_CHANGED` compares semantic content rather than
merely proving that two captures occurred.

A `PARTIAL` or `INCOHERENT` post-observation cannot pass verification even if
one requested fragment is present; it returns `INCONCLUSIVE`.

Low-level success and verification remain separate. A completed action may
return verification `FAILED` or `INCONCLUSIVE`. If post-action observation is
unavailable, execution is `UNKNOWN_OUTCOME` with the before-state reference;
recovery may re-observe but must not blindly repeat the mutation.

## Consequences

### Positive

- The first Android mutations use the existing protocol and non-bypassable
  policy path.
- Stale node ids and changed UI descriptors fail before action.
- Message send controls can require L3 confirmation even though tap has an L1
  baseline.
- Open-app and system navigation cannot become arbitrary Intents/actions.
- Every accepted mutation attempts a bounded post-action observation and
  explicit verification.

### Costs and limits

- Coordinate tap/long-press and L4/L5 targets are intentionally unavailable.
- Semantic ids are short-lived capture-local references, not durable Skills.
- Keyword risk classification needs localization and app-specific policy
  hardening; unknown semantic controls are conservatively L3.
- UI capture and gesture behavior still require emulator/OEM validation.
- Post-action polling is a local bridge bound, not the complete Recovery
  Engine; ADR-0006 remains required before automated retry.
- Artifact retrieval and production broker transport are still absent, so
  this is not yet an installable end-user control path.

## Rejected alternatives

### Execute any Accessibility action id supplied by Hermes

Rejected because it creates a raw bypass around the capability catalog and
Permission Gate.

### Keep Android node objects from screen capture until a later tap

Rejected because nodes are mutable/stale framework objects and would couple
protocol state to Accessibility implementation lifetime.

### Treat every tap as L1

Rejected because Send/Delete/Pay are materially different actions even when
implemented by the same primitive.

### Report action acceptance as verified task success

Rejected because Android acceptance does not prove screen transition or user
goal completion.

## Compliance checks

1. Cross-state targets fail protocol validation in Python and Kotlin.
2. Android PEP denial, stale state, target drift and risk upgrade occur before
   provider execution.
3. L4/L5 and coordinate taps fail closed.
4. The semantic registry is bounded, expires, stores keyed signatures and
   retains no Android object.
5. Open app creates only an explicit launcher Intent for the signed package,
   under an exact MAIN/LAUNCHER visibility query.
6. Gesture paths and durations are bounded and never automatically retried.
7. Every successful execution has before/after states and a separate
   verification result; missing post-state is `UNKNOWN_OUTCOME`.
8. Source and built APK checks allow the exact reviewed gesture profile and no
   extra Android permission/component/Accessibility flag.
