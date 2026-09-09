# ADR-0009: Protected Screenshot Capture

- Status: Accepted
- Date: 2026-09-09
- Decision owners: Hermes Mobile Runtime maintainers
- Phase: 1 / HMR-109

> Gesture-disablement in this historical screenshot decision is superseded by
> ADR-0010's guarded HMR-110 navigation path. Its screenshot authority and
> artifact limits remain authoritative.

## Context

Semantic Accessibility trees do not describe canvas, video, custom rendering
or many WebView surfaces. The Runtime therefore needs `phone.screenshot` as a
vision fallback, but a screenshot is D3 content that can contain credentials,
messages, photos and other applications. It must not become a caller-chosen
file write, an ordinary Tool payload or an unbounded memory operation.

Android API 30 provides `AccessibilityService.takeScreenshot` when the service
declares `canTakeScreenshot=true`. This widens read authority but does not
require MediaProjection, external storage, gestures or a new exported Android
component.

## Decision

### 1. Reuse the protected Accessibility service

The existing system-bound service declares screenshot authority and keeps its
event subscription restricted to `typeWindowStateChanged`. It still has no
gesture authority, overlay, Binder command endpoint, MediaProjection session,
storage permission or caller-supplied output path. Capture occurs only after
the closed Router and Android PEP accept an authorized `phone.screenshot`.

### 2. Bind capture to one live foreground generation

Immediately before capture, Android reads the active root only to validate its
package/window identity against the retained foreground generation. This live
check may renew a stale read observation; it does not make stale state valid
for mutation. The screenshot result is committed only if the exact state id
remains current. A window event during capture produces
`SCREENSHOT_WINDOW_CHANGED`, deletes the new artifact and requires
re-observation.

`before_state` identifies the validated generation. A successful capture
creates a new `after_state` with fingerprint basis `SCREENSHOT`. Screenshot
fingerprints are comparable only with another screenshot fingerprint.
Transition remains distinct from task verification.

### 3. Bounded capture and encoding

The protocol reserves validated display ids 0–7. The V0.1 Android adapter
implements only the default display (`0`) and rejects other ids before Android
capture so foreground correlation cannot be misattributed. It supports
optional bounded crops and PNG or lossless WebP, and enforces:

- Android capture callback deadline: 1,000 ms;
- source dimensions: at most 32,768 pixels per axis;
- selected crop: at most 16,777,216 pixels; and
- encoded artifact: at most 16 MiB through a bounded output stream.

Crop arithmetic uses widened integers before bounds checks. Hardware buffers
and temporary bitmaps are released, and the encoded plaintext array is zeroed
after the encrypted store copies it. Oversized images fail with a typed
recoverable result; the Runtime may request a crop rather than retry forever.

### 4. Protected artifact and keyed fingerprint

PNG/WebP bytes enter the existing AES-256-GCM process-local `ArtifactStore` as
D3 `EPHEMERAL` data with a five-minute expiry. The keyed HMAC-SHA256 artifact
digest is also the state fingerprint, preventing an equality oracle based on
an ordinary public hash. Tool results, PhoneState and Audit contain only the
closed `ArtifactRef` metadata.

The store now permits artifacts up to 16 MiB; semantic tree construction
retains its independent 1 MiB limit. The store still has no content read API.
Production retrieval must be separately authorized, audited, encrypted in
transport and deleted on expiry.

### 5. Stable failure policy

Android failures are normalized without exception text or pixels. Revoked
Accessibility and secure-window failures are not automatic retries. Rate
limit, timeout and internal failures permit bounded retry. Invalid display,
invalid crop and oversized image require rejection or replanning. Window
change requires re-observation.

## Consequences

### Positive

- Canvas and custom-rendered apps now have a bounded vision fallback.
- No screenshot bytes enter protocol JSON, logs, Audit or public storage.
- Window races fail closed and the result has coherent before/after states.
- Android authority remains read-only and concentrated in one reviewed
  system-bound service.

### Costs and limits

- The user must enable an Accessibility service with screen-read and
  screenshot capability.
- Some secure windows intentionally cannot be captured.
- Full-resolution lossless images may exceed 16 MiB and require a crop.
- The current in-memory artifact cannot yet be consumed by remote Hermes;
  authorized/audited artifact retrieval remains transport work.
- A dedicated in-app capture indicator/history UI is not implemented yet;
  release transport must surface capture through user-visible task Audit.
- Physical-device and OEM validation is still required by HMR-113/114.

## Rejected alternatives

### MediaProjection for every capture

Rejected for V0.1 because it adds consent-session lifecycle, foreground
service and projection-token complexity while the already required
Accessibility service has a bounded API 30 capture mechanism.

### Save screenshots to caller paths or shared media

Rejected because it bypasses artifact retention, access control and deletion.

### Return base64 image data in Tool results

Rejected because normal request history, model logs and retries would copy D3
content.

### Use unkeyed pixel hashes

Rejected because hidden screenshots can become equality and dictionary-attack
oracles.

## Compliance checks

1. Source and built APK require screenshot authority and still reject gesture
   authority or any permission beyond `INTERNET`.
2. Router/PEP denial occurs before capture.
3. Crop, dimension, pixel, callback and encoded-size bounds are explicit.
   Secondary displays fail closed until display-specific state exists.
4. Secure-window, revoked-access, timeout and window-race failures are typed.
5. A window change prevents state/artifact publication.
6. Result JSON contains only ArtifactRef metadata, never screenshot bytes.
7. Screenshot content is encrypted, keyed-digested, short-lived and zeroed
   after storage.
