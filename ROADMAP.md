# Hermes Mobile Runtime Roadmap

> Status: Phase 1 foundation in progress; minimal PhoneState/Observer in review
> Last updated: 2026-09-02

## 1. Delivery policy

Reliability → Security → Observability → Maintainability → Features → Demo effect.

No phase exits on a demo alone. A phase exits only when its contracts, tests, security controls, documentation and unresolved risks are recorded.

## 2. Phase 0 — Open Source Audit

Status: **Complete for architecture selection.**

Delivered:

- [`docs/research/open-source-comparison.md`](docs/research/open-source-comparison.md)
- [`ARCHITECTURE.md`](ARCHITECTURE.md)
- [`SECURITY.md`](SECURITY.md)
- This roadmap

Decision:

- Fork `NousResearch/hermes-agent` as Primary Base Repository.
- Adapt native capability code from `raulvidis/hermes-android` with pinned provenance.
- Use Mobilerun, AndroidWorld, MobileAgent, Open-AutoGLM, Android-MCP and hermes-mobile as scoped references.
- Do not enter large-scale refactoring until ADR-0001–0003 and repository import are complete.

Repository prerequisites now have the following status:

| Prerequisite | Status |
|---|---|
| Pin upstream SHAs and define upstream workflow | Complete; ADR-0001 accepted |
| Record hermes-android license and import ledger | Complete; no source imported |
| Run Hermes baseline checks | Recorded in [`docs/testing/phase-1-baseline.md`](docs/testing/phase-1-baseline.md); restricted-workspace suite is not green |
| Run Android baseline build/tests | Blocked until CI provides JDK 17 plus SDK 34 for the untouched upstream baseline and SDK 36 for HMR-101 |
| Produce dependency/license SBOM policy and artifacts | Policy and manual inventory in HMR-002; generated release artifacts pending CI |
| Approve detailed threat model and data classification | Complete; Phase 1 baseline merged |

## 3. First engineering backlog

### P0 architecture and risk

| ID | Task | Result |
|---|---|---|
| HMR-001 | ADR: repository composition and upstream boundaries | Complete; ADR-0001 accepted |
| HMR-002 | Third-party license inventory and SBOM policy | Complete; baseline policy and manual inventory merged |
| HMR-003 | Threat model and data classification | Complete; abuse cases, trust boundaries and retention merged |
| HMR-004 | ADR: Mobile Agent Protocol V0.1 | Complete; ADR-0002 accepted |
| HMR-005 | ADR: executor-enforced Permission Gate | Complete; ADR-0003 accepted |
| HMR-006 | Android device/API compatibility matrix | Complete; API 30/33/35/36 blocking, API 37 canary, Pixel + Samsung release gates |

### P0 Phase 1 foundation

| ID | Task | Result |
|---|---|---|
| HMR-101 | Bootstrap Android bridge with upstream provenance | Complete; zero-permission skeleton, pinned build and CI evidence lane |
| HMR-102 | TLS-only enrollment, device identity and replay defense | Complete; ADR-0005, Keystore identity, TLS policy and persistent replay ledger |
| HMR-103 | Protocol codec and compatibility negotiation | Complete; closed schemas, Python/Kotlin codecs, bundle integrity and shared golden tests |
| HMR-104 | Capability Registry and Tool Router | Complete; closed catalog and fail-closed host/device routing path |
| HMR-105 | `phone.current_app` vertical slice | Complete; protected window observer, L0 route, state refs and redacted audit seam |
| HMR-106 | Minimal PhoneState/Observer | In review; ADR-0004, protocol 0.1.1, coherent foreground generations and typed transitions |
| HMR-107 | Append-only redacted Audit | Task/span/request/policy/state correlation |

### P1 Phase 1 capability set

| ID | Task | Result |
|---|---|---|
| HMR-108 | `phone.read_screen` semantic nodes | Normalized hierarchy and fixtures |
| HMR-109 | Protected `phone.screenshot` artifact | Hash, access control and retention |
| HMR-110 | Navigation action set | Execution/verification split |
| HMR-111 | Notifications and device state | Cursor, dedupe, sensitivity and state |
| HMR-112 | Bounded wait/transition policy | Deadline, cancellation and retry limit |
| HMR-113 | Emulator contract-test harness | Dialog/keyboard/slow/UI-change fixtures |
| HMR-114 | CI quality gates | Python/Kotlin/schema/lint/license/security |

Detailed acceptance criteria are in the research report §13.

## 4. Phase 1 — Android Execution Bridge

Goal: prove a secure, observable vertical slice before porting the entire Android tool surface.

### Milestones

1. Baseline and provenance
   - Import/fork primary repository.
   - Pin Hermes Agent and hermes-android SHAs.
   - Establish third-party notice and patch inventory.
   - Build/test unmodified baselines.
   - Apply the API/OEM/degradation baseline from [`docs/testing/android-compatibility-matrix.md`](docs/testing/android-compatibility-matrix.md).
2. Protocol kernel
   - Approve ADR-0002.
   - Implement schemas, codec, compatibility negotiation and golden fixtures.
3. Security kernel
   - Approve ADR-0003.
   - Implement Runtime PDP, Android PEP, TLS enrollment and audit skeleton.
4. Read-only vertical slice
   - Deliver `phone.current_app` end to end.
   - Add minimal PhoneState and deterministic error ownership.
5. Observation
   - Add `read_screen`, screenshot/hash, focus/dialog/keyboard observation.
6. Navigation
   - Add tap, long press, type, swipe, back, home, open app and bounded wait.
7. Notifications and device state
   - Add notification cursor/dedupe and read-only device state.
8. Hardening
   - Run contract, emulator, compatibility and security suites.

### Phase 1 exit criteria

- All 13 V0.1 tools have versioned schemas even if some adapters are feature-gated.
- Implemented tools cannot bypass Router/Gate/Audit.
- Read and action tools return before/after state references and typed errors.
- State-changing actions have an explicit verification result.
- TLS is default; release builds reject insecure enrollment.
- Revoked Android permissions degrade to `CAPABILITY_UNAVAILABLE`/`PERMISSION_DENIED` without crash.
- Dialog, slow page, keyboard obstruction and changed element fixtures cause re-observation, not blind continuation.
- No infinite retry and no Shizuku/Shell in the release surface.
- Python/Kotlin unit and protocol contract tests, Android build, lint and license gates pass.

## 5. Later phases

| Phase | Goal | Entry condition | Exit signal |
|---:|---|---|---|
| 2 | Unified Tool Protocol | Phase 1 secure slice stable | All V0.1 tools conform; backward-compat tests |
| 3 | State Observer | Protocol/state refs stable | Atomic PhoneState, transitions and artifacts tested |
| 4 | Permission Gate | Read/navigation stable | L0–L5 policy, confirmation binding and device PEP audited |
| 5 | Recovery Engine | Typed errors + Observer | Bounded retry/re-observe/relaunch/replan/ask-user scenarios pass |
| 6 | Event Bus | Gate and audit stable | MobileEvent cursor/dedupe/backpressure and safe dispatch |
| 7 | Mobile Skills | Repeated flows measurable | Versioned skills with pre/postconditions and permissions |
| 8 | Skill Learning | Skill validation harness | Candidate → parameterize → repeated validation → publish/rollback |
| 9 | Memory | Sensitivity/retention policy | Mobile context memory is scoped, inspectable and deletable |
| 10 | Local/Cloud Hybrid | Runtime stable and profiled | Policy-based routing, offline degradation and model privacy tests |

## 6. MVP release gates

| Test | Required proof |
|---|---|
| Spotify work playlist | MediaSession/native path preferred; Accessibility fallback; playback state verified |
| Who messaged me | Notification cursor/dedupe; sensitive content policy; timestamp/source trace |
| Open received address in maps | Parsed address; allowlisted `geo:` Intent; target app/route state verified |
| Prepare message to contact | Contact disambiguation; draft verification; final send bound to L3 confirmation |
| Dialog/delay/UI change | Re-observe and bounded recovery; deterministic escalation when exhausted |

## 7. Engineering metrics

- False-success rate: executor success but verification failure.
- Recovery success rate by failure type and attempt count.
- P50/P95 observation and action latency.
- Percentage of actions with complete before/after/audit linkage.
- Permission denials, confirmations and expired/replayed confirmation attempts.
- Protocol compatibility failures by client/server version.
- Skill success rate by app version.
- Sensitive artifact retention/deletion compliance.

## 8. Deferred until explicitly scheduled

- Shizuku/Shell and APK installation.
- Financial/destructive actions.
- Always-on location/clipboard triggers.
- Vision-first automation.
- Permanent automatic Skill Learning.
- Supporting broad app catalogs before the five MVP tests are stable.
