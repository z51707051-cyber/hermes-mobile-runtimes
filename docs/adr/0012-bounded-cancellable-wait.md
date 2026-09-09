# ADR-0012: Bounded and Cancellable Wait

- Status: Accepted
- Date: 2026-09-09
- Phase: 1 — Android Execution Bridge

## Context

`phone.wait` is the final V0.1 capability needed for bounded navigation flows.
A fixed sleep is not sufficient: it can run after the task or authorization
expires, cannot react to cancellation, and provides no evidence that a page,
foreground application or requested text actually appeared. Reusing
`phone.read_screen` for every poll would also create many encrypted UI-tree
artifacts that no caller requested.

The protocol already bounds `timeout_ms` to 1–30,000 ms and permits an optional
`STATE_CHANGED`, `FOREGROUND_APP_IS` or `TEXT_PRESENT` condition. The Android
execution plane must preserve the same Router, PEP, result validation and
PhoneState boundaries as every other capability.

## Decision

1. Register one L0 `phone.wait` provider behind the Android Router and PEP.
2. A wait ends at the earliest of:
   - the requested `timeout_ms`;
   - the action deadline;
   - the execution-authorization expiry;
   - process-local cancellation; or
   - thread interruption.
3. Polling uses an absolute monotonic deadline and sleeps for at most 100 ms at
   a time. The provider performs no action retry and never starts background
   work that can outlive the result.
4. A request without a condition is a bounded timer. It does not require
   Accessibility. If its full requested interval completes it returns
   `SUCCEEDED`; if authorization/task time runs out first it returns
   `TIMED_OUT`.
5. A conditional request requires a live Accessibility semantic-probe source.
   It captures at most 500 nodes and 20,000 text characters. Only `COMPLETE`
   observations may satisfy a condition.
6. `STATE_CHANGED` compares protected, same-basis semantic fingerprints, not
   state ids or capture timestamps. Foreground package comparison is exact and
   text presence is a case-sensitive substring over protected visible text.
7. Semantic probes publish PhoneState generations but do not create a UI-tree
   artifact, register action targets or retain plaintext. A process-random
   HMAC-SHA256 key protects the stable-in-process probe fingerprint; the
   canonical tree byte array is wiped after evaluation.
8. Cancellation is keyed by request id and exposed only as a process-local
   stop operation. It cannot start an action or widen authority. Duplicate
   active request ids fail closed.
9. Timeout, cancellation, observation loss and success remain distinct typed
   results. Raw observed text never enters result metadata, error details or
   Audit.

## Result semantics

| Outcome | Execution status | Verification | Recoverable |
|---|---|---|---|
| Timer fully elapsed | `SUCCEEDED` | `NOT_APPLICABLE` | No |
| Condition observed | `SUCCEEDED` | `PASSED` | No |
| Condition not observed | `TIMED_OUT` | `FAILED` | Yes, by re-observation |
| Action/authorization deadline first | `TIMED_OUT` | `INCONCLUSIVE` | No |
| Explicit cancellation/interruption | `CANCELLED` | `INCONCLUSIVE` | No |
| Accessibility lost during condition polling | `FAILED` | `INCONCLUSIVE` | Yes |

## Consequences

### Positive

- Slow pages can be awaited without blind action repetition.
- Conditions use coherent semantic evidence and cannot pass on partial trees.
- Cancellation latency is bounded to one polling interval under normal thread
  scheduling.
- Polling does not accumulate large protected artifacts.

### Costs and limits

- Conditional waits require Accessibility; timer waits do not.
- Polling traverses the active tree and therefore consumes CPU while active.
- V0.1 does not expose event-driven window/content subscriptions, so the
  provider uses a bounded 100 ms poll.
- A process restart changes the probe HMAC key, making fingerprints from the
  previous process intentionally incomparable.

## Rejected alternatives

- **One fixed `SystemClock.sleep`:** not cancellable and supplies no condition
  evidence.
- **State-id comparison:** every capture can create a new state id without a
  semantic UI change.
- **Repeated `phone.read_screen`:** creates unnecessary sensitive artifacts and
  couples an internal wait to externally retrievable evidence.
- **Unlimited retry after timeout:** violates task budgets and can amplify both
  resource consumption and unintended actions.
