# HMR-111 Notification and Device-state Verification

HMR-111 adds read-only `phone.notifications` and `phone.device_state`
providers. It does not add Event Bus dispatch, notification actions, Bluetooth
authority, artifact retrieval or production transport.

## Automated evidence

| Check | Evidence |
|---|---|
| Notification ledger | Dedupe, update/remove, filters, cursors, expiry, Unicode bounds and truncation unit tests |
| Provider boundary | D3/D2 `ArtifactRef` metadata only; PhoneState is optional |
| PEP | Disconnected notification access is rejected before capture |
| Manifest | Source and APK checks require exact permissions and protected services |
| Android quality | Gradle unit tests, lint, APK inspection and SBOM workflow |

## Manual device acceptance

1. Install the debug APK on API 30+ and enable **Hermes notification observer**
   in Android notification-access settings.
2. Post and update notifications from a test package. Query a snapshot and
   then changes with its cursor; verify dedupe and `UPDATED` semantics.
3. Disable access. Verify capability removal and old-cursor invalidation after
   reconnect.
4. Query all device-state fields. Verify no SSID/BSSID/IP/location/device ids
   appear and Bluetooth is explicitly withheld.

CI establishes source/build correctness. This device run is required before
HMR-111 is considered device-validated.
