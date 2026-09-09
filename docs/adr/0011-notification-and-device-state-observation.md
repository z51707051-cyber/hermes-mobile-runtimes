# ADR-0011: Notification and Device-state Observation

- Status: Accepted
- Date: 2026-09-09
- Decision owners: Hermes Mobile Runtime maintainers
- Phase: 1 / HMR-111

## Context

The MVP must answer which notification arrived and inspect basic device state.
Notification content is attacker-controlled D3 data and may include private
messages or prompt injection. Nearby Bluetooth and Wi-Fi identity can reveal
location. Neither observation may become an unaudited event-to-action channel.

## Decision

### System-bound notification listener

Android binds one exported `NotificationListenerService` guarded by
`BIND_NOTIFICATION_LISTENER_SERVICE`. The user must explicitly enable access
in Android Settings. The Runtime cannot enable it or simulate consent.

Callbacks project framework objects immediately into bounded primitive fields:
key, source package, timestamps, title, text, subtext, category, ongoing and
clearable. Icons, actions, intents, `RemoteViews`, people/contact objects and
the original Android notification are not retained.

The memory-only ledger retains at most 100 active projections and 256 ordered
changes. HMAC-SHA256 produces process-local ids and dedupe digests. Identical
callbacks do not create duplicate changes. Snapshot truncation is explicit.
Opaque cursors bind a random process session and sequence; invalid, future and
expired cursors fail with typed recoverable errors. Disconnect clears content
and rotates the session. No durable notification history is created.

`phone.notifications` returns a five-minute encrypted D3 artifact reference,
never notification content in Tool JSON, logs or Audit. Notification text is
untrusted data and cannot grant authority or directly start a task. HMR-111
does not implement Event Bus dispatch.

### Least-authority device-state projection

`phone.device_state` reads only battery, charging, network
reachability/validation/metering/transports, Wi-Fi transport presence, screen
interactivity and locale. It requests only the normal `ACCESS_NETWORK_STATE`
permission. It does not collect SSID, BSSID, IP address, cell identity,
location, nearby devices, identifiers or scan results.

Bluetooth state is explicitly withheld because the Runtime does not request
`BLUETOOTH_CONNECT`. Device state is returned as a five-minute encrypted D2
artifact reference.

### Independent protected paths

Both L0 providers enter through the closed registry, broker-issued
`AuthorizedAction` and Android PEP. Current PhoneState is optional context, not
a prerequisite. Capability availability never implies authorization, and the
production PEP remains deny-all until authenticated transport is composed.

## Consequences

- Notification access requires explicit user setup.
- Process restart invalidates notification cursors.
- Bluetooth state remains unavailable in HMR-111.
- Artifact retrieval and production transport are still absent, so the APK is
  not yet an end-user Hermes control path.

## Rejected alternatives

- Persisting raw notifications was rejected because retention and deletion
  controls do not yet exist.
- Adding Bluetooth/location permissions was rejected as disproportionate.
- Direct notification-to-task execution was rejected because it would turn
  untrusted text into an instruction channel.

## Compliance checks

1. Manifest checks require exactly two protected services and only `INTERNET`
   plus `ACCESS_NETWORK_STATE`.
2. Unit tests cover dedupe, filters, removal, cursors, Unicode bounds and
   truncation.
3. Provider tests prove content cannot fit the result and revocation prevents
   capture.
4. No notification API exposes action intents or mutations.
