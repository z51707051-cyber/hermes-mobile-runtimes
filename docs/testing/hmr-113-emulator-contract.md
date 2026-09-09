# HMR-113 Emulator Contract Verification

HMR-113 adds a synthetic fixture APK and clean API 30/API 36 emulator lanes.
The tests use the packaged bridge, the real Android Accessibility service and
the real protected Router/PEP path.

## Blocking scenarios

| Scenario | Input | Required result |
|---|---|---|
| Clean install | Accessibility disabled | `phone.wait` remains available; Accessibility-bound capabilities are absent |
| Slow page | Loading text changes after 3 s | `phone.wait(TEXT_PRESENT)` succeeds after re-observation, not immediately |
| Delayed dialog | AlertDialog appears after 2 s | Dialog text is observed; `phone.back` dismisses it with `TEXT_ABSENT` verification |
| Keyboard | Focused editor requests the system IME | Insets-driven `Keyboard visible` evidence is observed through Accessibility |
| UI change | Semantic label/description changes after 2 s | `phone.wait(STATE_CHANGED)` passes on a comparable semantic fingerprint |
| Revoke | Accessibility secure setting removed | Accessibility-bound capabilities are absent without process crash |

## CI execution

For each API level, CI:

1. assembles the bridge, test and fixture APKs under strict dependency
   verification;
2. creates a new Google APIs x86_64 AVD without snapshots;
3. records the installed `system.img` SHA-256;
4. installs all three APKs with no accounts or user data;
5. runs the ungranted, granted and revoked Instrumentation modes; and
6. uploads bounded diagnostic evidence for 14 days.

The contract runner emits exactly one terminal marker:

```text
HMR_CONTRACT_STATUS=PASSED mode=<mode>
```

Any missing marker, assertion failure, APK/install failure, boot timeout or
non-green build fails the matrix lane.

## Remaining coverage

HMR-114 owns API 33/35 nightly lanes, permission revocation during an active
wait, process death/reboot, rotation, screenshot/gesture device coverage and
quality-gate consolidation. Pixel and Samsung validation remains a release
gate and cannot be claimed from emulator evidence.
