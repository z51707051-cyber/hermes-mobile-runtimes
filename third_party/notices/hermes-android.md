# hermes-android Provenance Notice

## Upstream

- Project: `hermes-android`
- Repository: https://github.com/raulvidis/hermes-android
- Pinned commit: `fbd623840bdcf1b38c835c7d2973c7b667d55da8`
- Commit date: 2026-08-19
- Upstream version: `0.4.1`
- License: MIT
- License SHA-256: `a6dbadb8acae262b20da52d7d1ea600fc736fc37e546563118bccbffddf05216`

The license text is preserved in
[`hermes-android-LICENSE.txt`](hermes-android-LICENSE.txt).

## Current import status

No hermes-android source file has been imported into Hermes Mobile Runtime at
this baseline. The repository is currently an implementation reference only.

## Intended reusable capability areas

- Accessibility service lifecycle and semantic UI-tree traversal.
- Gesture dispatch for tap, long press and swipe.
- Focused text entry.
- Screenshot capture primitives.
- Foreground application observation.
- Notification listener primitives.
- Explicit, allowlisted Intent adapters where policy permits.
- Android capability and hardware detection.

## Explicitly excluded upstream areas

- Python tool definitions and relay implementation.
- Pairing-code trust model and cleartext transport.
- Macro execution.
- Arbitrary Intent or broadcast dispatch.
- Direct SMS/call operations.
- Clipboard, location, contacts, microphone and screen recording in V0.1.
- Any route that bypasses device-side permission enforcement and audit.

## Import ledger

When code is imported or substantially derived, append one row per coherent
file group. Do not overwrite historical rows.

| Downstream path | Upstream path(s) | Source SHA | Adaptation commit | Notes |
|---|---|---|---|---|
| _None_ | _None_ | `fbd623840bdcf1b38c835c7d2973c7b667d55da8` | _Not applicable_ | Baseline reference only |

## Update procedure

1. Review new upstream commits and security-sensitive manifest changes.
2. Update the pinned SHA in a dedicated Pull Request.
3. Add ledger rows for newly imported or changed file groups.
4. Preserve the MIT license and copyright notice.
5. Run Kotlin unit tests, Android lint/build, protocol contract tests and the
   Mobile Runtime security suite before merge.
