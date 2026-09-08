# ADR-0004: PhoneState Consistency and Artifact Storage

- Status: Accepted
- Date: 2026-09-02
- Decision owners: Hermes Mobile Runtime maintainers
- Phase: 1 / HMR-106

## Context

ADR-0002 defines `PhoneStateRef` as the safe state summary carried by Tool
results, but deliberately defers capture consistency, skew and storage. A
state-changing mobile action must not bind a current screenshot to an old UI
tree, reuse a pre-reconnect foreground claim, or treat an executor return value
as evidence that the screen changed.

HMR-105 introduced one narrow source: an Accessibility window-state callback
that retains package and class identity. It cannot retrieve window content,
take screenshots or perform gestures. HMR-106 must turn that source into the
first coherent state generation without pretending that window identity is a
pixel or semantic screen capture.

Package/activity history is D1/D2. UI trees, visible text and screenshots are
D3. The state contract therefore has to separate safe references and digests
from protected content and must not make a raw content hash into a new leakage
surface.

## Decision

### 1. Separate snapshot, reference and artifact

The Observer uses three distinct concepts:

- `PhoneStateSnapshot` is an immutable, device-local capture generation. It
  contains normalized component values, source metadata, monotonic capture
  times, completeness and stable capture errors.
- `PhoneStateRef` is the bounded protocol projection used in Tool results,
  Audit correlation and state preconditions. It never contains raw UI text,
  a UI hierarchy or screenshot bytes.
- `ArtifactRef` identifies separately protected D3 content. Artifact retrieval
  is separately authorized and audited as required by ADR-0002.

`state_id` is an opaque random identifier, not a content hash. Repeated capture
of the same surface produces a new state id and links to the previous state id.

### 2. Capture clocks and freshness

Each generation records both:

- UTC epoch time for protocol display and user-facing audit; and
- Android elapsed realtime for freshness and ordering.

Wall-clock changes never make a stale state fresh. Negative elapsed deltas are
clamped to zero only for reporting and must be surfaced as a clock/capture
fault when sources are fused later.

The V0.1 hard maximum state age remains 5,000 ms. A provider or policy may use
a stricter maximum. Android rechecks freshness immediately before dispatch;
the Runtime's earlier check is not sufficient.

Disconnecting the source clears its last current generation. Reconnecting
requires a new event and cannot resurrect a pre-disconnect state.

### 3. Coherent generations and skew

One foreground window callback creates one atomic foreground generation under
a single synchronization boundary. Its package, class claim, fingerprint,
capture status, transition and clocks cannot come from different callbacks.

Future UI-tree and screenshot sources retain their own component generation
and monotonic timestamp. The assembler may publish a fused state only when all
required components belong to the requested foreground/window generation and
their capture-time skew is at most 500 ms. The configurable value may be
lowered; V0.1 must not exceed 1,000 ms.

If an optional component is absent, the state is `PARTIAL` with stable error
codes. If required components conflict or exceed the skew limit, the state is
`INCOHERENT`. `INCOHERENT` state may be reported for diagnosis but cannot
authorize a mutation. Callers must re-observe; they must not splice fields from
different states.

### 4. Capture status and errors

`capture_status` is one of:

- `COMPLETE`: every component promised by this capture profile is present;
- `PARTIAL`: the safe core exists but one or more optional fields are absent;
- `INCOHERENT`: required components conflict, are too far apart in time or
  cannot be assigned to one foreground generation.

`capture_errors` contains only stable, non-content codes. It never contains
exception text, UI text, package history, paths or tokens. HMR-106 uses
`FOREGROUND_ACTIVITY_UNAVAILABLE` when the Accessibility class claim is absent
or invalid.

### 5. Foreground identity is an untrusted claim

The foreground package and class originate from Android Accessibility events.
They are useful OS-mediated observations but remain untrusted app-controlled
claims under the threat model. The field named `foreground_activity` is the
normalized window-state class claim; consumers must not treat it as proof that
a specific Activity performed an action.

Package is required for `phone.current_app`. Activity may be null, in which
case the state is `PARTIAL`. Invalid identifiers are never copied into the
state.

### 6. Typed screen fingerprints

A fingerprint always includes a `basis` and SHA-256 digest. Supported protocol
bases are `WINDOW_IDENTITY`, `UI_HIERARCHY`, `SCREENSHOT` and `FUSED`.

HMR-106 implements only `WINDOW_IDENTITY`, calculated over a length-prefixed
canonical encoding of the already disclosed package and activity claim. It is
a navigation-level fingerprint: it can detect a changed foreground window but
cannot detect text, animation, list, dialog or pixel changes. It must never be
described as screenshot verification.

Future fingerprints derived from hidden D3 content use a device/session-scoped
keyed digest unless the underlying content is intentionally disclosed through
the same authorized artifact. A digest must not enable useful dictionary
attacks against low-entropy hidden content.

### 7. Transition semantics

`transition` describes the new generation relative to `previous_state_id`:

- `UNKNOWN`: there is no predecessor, either generation is not `COMPLETE`,
  fingerprints are incomparable, or the capture is incoherent;
- `NONE`: comparable fingerprints are equal;
- `CHANGED`: comparable fingerprints differ.

`CHANGED` means only that the selected fingerprint basis changed. It does not
prove the requested postcondition or complete the user task. Verification
remains a separate result.

### 8. Protocol patch 0.1.1

The normative V0.1 schema patch advances from `0.1.0` to `0.1.1`.
`PhoneStateRef` now requires:

- `previous_state_id`;
- `foreground_activity`;
- typed `screen_fingerprint`;
- `capture_status` and `capture_errors`;
- the existing id, clocks, device, package, transition and optional artifacts.

All fields are present even when their values are null or empty, so absence is
not confused with an old or permissive decoder. Schema-bundle negotiation
requires an exact digest match, and peers with the old shape fail closed.

### 9. Storage and retention

HMR-106 keeps only the latest normalized foreground generation in process
memory. It does not persist raw UI content or implement an artifact store.

HMR-108/109 must put UI trees and screenshots behind an `ArtifactStore`
interface that enforces sensitivity, encryption, retention class, expiry,
deletion and separately audited reads. Normal Runtime logs and Audit records
contain state/artifact ids and approved metadata only.

The maximum default lifecycle remains:

- package/activity transition metadata: 30 days when Audit policy retains it;
- UI tree or screenshot artifact: memory by default, at most 24 hours when a
  task explicitly requires persistence.

### 10. Preconditions and failure behavior

A state-bound action must bind its state id and maximum age. Before provider
dispatch Android validates that the referenced generation is still available,
fresh, coherent for the required fields and associated with the expected
foreground package/window. Any mismatch is a typed failure owned by the
Observer or Android PEP.

No caller may convert `PARTIAL`, `INCOHERENT`, `UNKNOWN` or a changed basis into
success by filling defaults. Recovery must request a new observation.

## Consequences

### Positive

- A state generation has deterministic freshness and transition semantics.
- Reconnect cannot silently reuse an old foreground claim.
- Window-level change detection is useful without expanding Accessibility
  authority.
- Later UI and screenshot providers have explicit coherence, sensitivity and
  artifact rules.
- Protocol consumers can distinguish unavailable fields from an old schema.

### Costs

- The protocol patch and schema-bundle digest change together.
- Every producer must populate explicit completeness and predecessor fields.
- Full visual change detection remains unavailable until HMR-108/109.
- Fused capture will need bounded coordination rather than independent reads.

## Rejected alternatives

### Call the window-identity digest a screenshot hash

Rejected because two different screens in one Activity can share the same
window identity. The typed basis prevents false verification claims.

### Use `state_id` as a content digest

Rejected because it leaks equality, couples identity to capture algorithms and
cannot represent repeated observations of unchanged content.

### Keep the last state across service reconnect

Rejected because a reconnect creates a new observation generation and the old
foreground claim may no longer be true.

### Inline UI trees or screenshots in PhoneStateRef

Rejected by ADR-0002 and the D3 data lifecycle. Content belongs in protected
artifacts.

### Let each provider assemble its own state

Rejected because independently sampled package, tree and screenshot fields
would create mixed generations and inconsistent verification.

## Compliance checks

1. First observation is `UNKNOWN`; equal and unequal comparable fingerprints
   produce `NONE` and `CHANGED` respectively.
2. A new state id is generated for every accepted callback and links to its
   predecessor.
3. Disconnect/reconnect requires a new observation.
4. Invalid package data is rejected; unavailable activity is explicit partial
   state.
5. Python and Kotlin accept the same `0.1.1` state fixture and reject an
   incomplete one.
6. The HMR-105 Accessibility configuration remains window-state-only with UI
   retrieval and gestures disabled.
7. No raw UI content or screenshot enters state JSON, Audit or logs.

## Follow-up decisions

- ADR-0006: Error taxonomy and bounded recovery policy.
- HMR-107: durable append-only redacted Audit.
- HMR-108: normalized semantic UI component capture.
- HMR-109: protected screenshot artifact and keyed visual fingerprint.
