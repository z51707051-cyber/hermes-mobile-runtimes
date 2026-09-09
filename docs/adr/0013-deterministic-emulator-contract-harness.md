# ADR-0013: Deterministic Emulator Contract Harness

- Status: Accepted
- Date: 2026-09-09
- Phase: 1 — Android Execution Bridge

## Context

Unit tests prove protocol and provider decisions but cannot prove that Android
actually binds Accessibility, exposes a dialog tree, reports an IME overlay or
delivers window transitions. The Phase 1 exit gate requires clean grant/revoke
coverage and repeatable dialog, keyboard, slow-page and changed-UI scenarios on
the minimum and latest supported Android APIs.

The harness must not add test commands, fake states or broad permissions to the
release APK. It must use synthetic content only, exercise the same Router/PEP
and service implementation as production, and retain enough evidence to debug
a failed CI run.

## Decision

1. Add a separate `fixture-app` APK containing only deterministic classic-View
   scenarios. Its exported Activity is installed only in the isolated emulator
   job and accepts a closed scenario name. It is never packaged in the bridge
   APK or release artifacts.
2. Add an `androidTest` Instrumentation runner to the bridge. It calls the real
   process-local Router with an allow-only test authorization delegate and
   submits schema-valid `AuthorizedAction` payloads. It does not call Android
   Accessibility helpers directly.
3. Run clean emulator lanes on API 30 and API 36 for every relevant PR. Each
   lane records the system-image SHA-256, device properties, instrumentation
   output, Accessibility state, window state and logcat.
4. The host test sequence is fixed:
   - clean install and prove Accessibility capabilities are unavailable;
   - enable the exact bridge service using emulator-only secure settings;
   - execute slow page, delayed dialog, verified Back, visible keyboard and
     semantic UI-change contracts;
   - revoke Accessibility and prove the capabilities disappear again.
5. Slow/dialog/keyboard/UI-change success is evaluated with `phone.wait` over
   real semantic captures. Dialog dismissal uses `phone.back` and requires
   `TEXT_ABSENT` post-action verification. This demonstrates
   Observe → Act → Observe → Verify rather than coordinate scripting.
6. The fixture uses no account, network, notification, contact, location,
   clipboard or real user content. CI evidence is retained for 14 days.
7. The harness uses platform Instrumentation directly and introduces no new
   runtime or Android-test library dependency. The fixture resolves the same
   reviewed dependency lock as the bridge.

## Consequences

### Positive

- Platform integration failures become reproducible on both supported
  boundary APIs.
- Accessibility grant/revoke is validated against the packaged APK rather than
  a fake service.
- Slow transitions and overlays prove that conditions are re-observed.
- Test-only control code remains outside all release source sets.

### Costs and limits

- Two clean emulator boots add CI time and consume hosted-runner resources.
- Emulator IME and Accessibility behavior does not replace Pixel/Samsung OEM
  release gates.
- API 33/35 and the broader chaos matrix remain nightly/release work for
  HMR-114.
- The synthetic fixture is an executable test oracle, not a supported app or a
  production command interface.

## Rejected alternatives

- **Unit fakes only:** cannot validate Android service binding, window roots or
  IME behavior.
- **ADB coordinate clicks as assertions:** repeat a UI-clicker pattern and do
  not prove Runtime observation or verification.
- **Fixture controls in the release Activity:** creates permanent attack and
  maintenance surface.
- **Real third-party apps/accounts in CI:** nondeterministic, privacy-sensitive
  and dependent on external versions and credentials.
