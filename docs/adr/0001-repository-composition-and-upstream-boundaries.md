# ADR-0001: Repository Composition and Upstream Boundaries

- Status: Accepted
- Date: 2026-08-28
- Decision owners: Hermes Mobile Runtime maintainers
- Phase: 1 / M1 Baseline

## Context

Hermes Mobile Runtime must extend an actively maintained agent without turning
the Android bridge into a second planner or creating a permanent fork that is
impossible to update. The two selected upstreams have different roles:

- `NousResearch/hermes-agent` is the control-plane base.
- `raulvidis/hermes-android` is a source of Android capability implementations.

The Android upstream is a useful prototype, but its current bridge exposes a
large privileged surface, permits cleartext traffic, uses pairing-code
authentication as the primary trust mechanism, and includes direct macro,
Intent, broadcast, SMS, call, clipboard, location, microphone and screen
recording operations. Those choices do not satisfy the Mobile Runtime security
model in `SECURITY.md`.

The Hermes upstream also requires a narrow core and stable per-conversation
toolsets. Mobile support therefore cannot be added by copying 42 Android tools
into the default Hermes tool schema.

## Decision

### 1. Primary repository

This repository remains a GitHub fork of `NousResearch/hermes-agent` and owns
the complete Hermes Mobile Runtime product. The pinned Phase 1 baseline is:

```text
upstream: https://github.com/NousResearch/hermes-agent
commit:   31e41eed347f62de312e59ccc822b30a635d4aba
license:  MIT
```

`origin/main` is downstream product history. `upstream/main` is read-only
upstream history. Downstream `main` is never force-pushed or rebased.

Upstream updates use a dedicated Pull Request named
`chore(upstream): sync hermes-agent @ <sha>`. The PR must record the old and new
upstream SHAs, resolve conflicts explicitly, and pass the baseline, protocol,
security and mobile test suites before merge.

### 2. Android capability source

The pinned Android implementation reference is:

```text
upstream: https://github.com/raulvidis/hermes-android
commit:   fbd623840bdcf1b38c835c7d2973c7b667d55da8
version:  0.4.1
license:  MIT
```

It is not added as a Git submodule, Git subtree or complete vendored copy.
Only reviewed capability code may be adapted into
`apps/mobile-bridge-android/`. Every imported or substantially derived group
of files must be mapped to its source path and source SHA in
`third_party/notices/hermes-android.md`, retain the MIT notice, and be covered
by tests in this repository.

The upstream Python toolset, relay, macro executor, public command surface and
pairing/security model are not imported. They may be consulted as references,
but Mobile Runtime provides its own versioned protocol, policy enforcement,
transport, audit and result types.

### 3. Code ownership boundaries

| Area | Location | Ownership rule |
|---|---|---|
| Hermes control plane | Existing Hermes packages | Keep upstream-compatible; no Android types |
| Mobile Runtime | `hermes_mobile/` | Downstream-owned protocol, routing, policy, observation, audit and recovery |
| Hermes exposure | Thin service-gated registration adapter | No raw bridge calls; not in the default core toolset |
| Android execution plane | `apps/mobile-bridge-android/` | Kotlin capability providers and device-side policy enforcement |
| Shared contracts | `hermes_mobile/protocol/` plus generated Kotlin artifacts | Versioned schemas; no Python or Android runtime objects on the wire |
| Third-party records | `third_party/notices/` | Source SHA, license, imported paths and patch history |

The Android app never depends on Hermes Python packages. Hermes never depends
on `AccessibilityNodeInfo`, Android services or transport-specific objects.
Both sides communicate only through the protocol approved in ADR-0002.

### 4. Tool surface

Mobile tools form a named, service-gated toolset. They are absent when no
enrolled Mobile Runtime is reachable, so ordinary Hermes sessions do not pay
the schema or prompt-cache cost. Skills and MCP cannot call a raw Android
transport; every operation enters the Mobile Runtime Tool Router, Permission
Gate and Audit path.

### 5. Security boundary

Code provenance does not confer trust. Adapted Android capability code runs
behind a new device-side Policy Enforcement Point. The following upstream
behaviours are prohibited in release builds:

- cleartext HTTP or WebSocket transport;
- arbitrary model-generated Intent, broadcast or shell execution;
- direct macro chains that bypass observe/verify and policy checks;
- pairing code as long-lived authentication;
- default exposure of SMS, calls, contacts, location, clipboard, microphone,
  package enumeration or screen recording;
- successful execution responses without post-action verification metadata.

V0.1 does not expose Shizuku, shell, APK installation, payments or transfers.

## Rejected alternatives

### Use `hermes-android` as the primary repository

Rejected because it duplicates Hermes-side tools and relay logic while lacking
the required Planner, Memory, Skills, MCP and long-lived agent control plane.

### Vendor the complete Android repository

Rejected because it imports unrelated high-risk capabilities and makes
security review, patch ownership and upstream comparison harder.

### Add `hermes-android` as a Git submodule

Rejected because the production bridge requires structural security changes,
not an unmodified checkout, and submodule state complicates Android builds and
release provenance.

### Copy the Python Android tools into Hermes core

Rejected because it creates duplicate implementations, expands every agent's
tool schema and permits direct coupling to the bridge protocol.

## Consequences

### Positive

- Hermes upgrades remain traceable and reviewable.
- Android provenance is explicit without inheriting the prototype's security
  model.
- Mobile capability stays at the edge of the Hermes core.
- Python and Kotlin can evolve independently behind a stable protocol.

### Costs

- Selected Android code must be ported and tested rather than copied wholesale.
- Upstream synchronization requires a regular, reviewed PR.
- Protocol fixtures and third-party provenance records become mandatory release
  artifacts.

## Compliance checks

Before ADR-0002 implementation begins:

1. `origin/main` contains this ADR and the Android notice.
2. Both pinned commits and licenses are reproducible.
3. The unmodified Hermes editable install, lockfile check, lint and baseline
   tests have recorded results.
4. The unmodified Android bridge build/test result or environment blocker is
   recorded.
5. No Android product code has been imported yet.

## Follow-up decisions

- ADR-0002: Mobile Agent Protocol V0.1.
- ADR-0003: Permission Gate enforcement.
- ADR-0004: PhoneState consistency and artifact storage — accepted.
- ADR-0005: Device identity, transport and key rotation.
- ADR-0006: Error taxonomy and bounded recovery.
