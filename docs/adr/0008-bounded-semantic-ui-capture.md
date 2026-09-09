# ADR-0008: Bounded Semantic UI Capture

- Status: Accepted
- Date: 2026-09-08
- Decision owners: Hermes Mobile Runtime maintainers
- Phase: 1 / HMR-108

> Gesture-disablement in this historical read-only decision is superseded by
> ADR-0010's guarded HMR-110 navigation path. Its capture and retention limits
> remain authoritative.

## Context

HMR-105/106 intentionally limited Accessibility to foreground package and
class claims. The Runtime now needs `phone.read_screen`, but Android UI trees
contain messages, account data, form values and adversarial app-controlled
text. Returning an `AccessibilityNodeInfo`, retaining event objects or putting
the tree directly in ordinary Tool JSON would violate ADR-0002 and ADR-0004.

The first semantic reader must also remain useful on large, malformed or
rapidly changing trees without turning Accessibility into a gesture channel.

## Decision

### 1. Reuse one read-only Accessibility service

The existing system-bound service enables `canRetrieveWindowContent=true` and
only `flagReportViewIds`. It continues to subscribe only to
`typeWindowStateChanged`, keeps `canPerformGestures=false`, requests no new
Android permission and exposes no Binder/network command endpoint.

The active hierarchy is traversed only after the protected Android Router and
PEP accept an authorized `phone.read_screen` action. Window callbacks still
retain identity only. Android node/event objects never leave the service and
are released immediately after primitive fields are copied.

### 2. Active window and coherence only

V0.1 accepts only `scope=ACTIVE_WINDOW`. The captured root package and Android
window id must match the current foreground generation. A disconnect, absent
root or mismatch returns a typed recoverable observation failure. The Runtime
does not combine fields from different windows.

A successful capture creates a new immutable PhoneState generation linked to
its predecessor. Its fingerprint basis is `UI_HIERARCHY`; the digest is keyed
and is also bound to the protected artifact. Canonical fingerprint content
omits capture time—PhoneState carries observation time separately—so identical
UI trees remain comparable across observations. A first comparison against a
window-identity fingerprint is `UNKNOWN`, not a false navigation result.

### 3. Closed normalized tree

The artifact contains a flat preorder node list with generated capture-local
ids, parent/depth/child position, bounded class/resource metadata, semantic
role, normalized visible strings, screen bounds and reviewed boolean states.
It contains no Android object, action handle, method name or executable
selector.

Limits are enforced during traversal:

- request `max_nodes`: 1–500, default 200;
- request `max_text_chars`: 1–20,000, default 10,000;
- depth: at most 64;
- visited children per node: at most 500; and
- encoded artifact: at most 1 MiB.

Limit exhaustion produces stable capture codes and a `PARTIAL` PhoneState.
The traversal never fills missing content with invented values.

### 4. Redaction and hostile content

Password node text and content descriptions are always withheld. Control
characters are normalized and text truncation preserves Unicode surrogate
pairs. UI text remains untrusted data; it is never interpreted inside the
Android bridge and never enters logs or execution Audit.

### 5. Protected artifact

The canonical tree is written to a process-local `ArtifactStore` as D3,
`EPHEMERAL`, with a five-minute expiry. The store:

- encrypts content with AES-256-GCM and a unique 96-bit nonce;
- produces a separately keyed HMAC-SHA256 content digest;
- requires purpose-separated keys;
- zeroes its temporary plaintext copy;
- supports explicit deletion and expiry purge; and
- returns only a closed `ArtifactRef` in Tool/PhoneState JSON.

The current store intentionally exposes no content read method. Artifact
retrieval must be a separately authorized and audited transport operation; it
will be added with the production bridge transport rather than as a raw local
escape hatch. Until then, HMR-108 proves capture/storage but is not a complete
end-user screen-reading path.

### 6. No mutation authority

This decision does not enable Accessibility actions, gestures, global actions,
shell, Intent dispatch, notification access or interactive-window enumeration.
HMR-110 must introduce mutation only behind a new PEP-reviewed provider.

## Consequences

### Positive

- Hermes gains a stable semantic UI abstraction without coupling to Android
  node objects.
- Capture is bounded against huge or malformed app trees.
- D3 content is encrypted and short-lived rather than copied into normal JSON
  history.
- Window mismatch and disconnect races fail closed.
- The same normalized structure can later support semantic selectors and
  verification.

### Costs and limits

- Accessibility authority expands from window identity to active-window read.
- Some custom/canvas/WebView surfaces expose sparse semantics and will need
  screenshot/vision fallback in HMR-109.
- The process-local store is lost on restart by design.
- A production authorized artifact retrieval route is still required before a
  remote Hermes planner can consume the tree.

## Rejected alternatives

### Inline UI tree in ToolExecutionResult

Rejected because normal protocol logs, retries and Audit integrations could
silently replicate D3 content.

### Capture on every Accessibility event

Rejected because it collects private content without a task and creates an
unbounded event-driven data stream.

### Enable all windows or gestures now

Rejected because neither is required by `ACTIVE_WINDOW` read and both widen
authority before a concrete reviewed consumer exists.

### Persist plaintext JSON on disk

Rejected because UI content is D3 and ephemeral by default.

## Compliance checks

1. Source and built APK policy require UI read, `flagReportViewIds` and no
   gesture authority.
2. Router/PEP denial occurs before provider capture.
3. Node, text, depth and artifact byte limits have behavior tests.
4. Password content is absent from normalized output.
5. UI state requires current package/window correlation.
6. Result JSON contains ArtifactRef metadata, not tree content.
7. Artifact keys are separate, nonce reuse is rejected and expiry deletes the
   encrypted entry.
