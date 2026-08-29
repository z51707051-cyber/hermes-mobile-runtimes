# ADR-0005: Device Identity, Enrollment, Transport and Key Rotation

- Status: Accepted
- Date: 2026-08-29
- Decision owners: Hermes Mobile Runtime maintainers
- Phase: 1 / HMR-102

## Context

The Runtime broker and Android bridge are different trust domains. A network
attacker must not be able to read, alter, inject, replay or reorder device
traffic, and a copied app backup must not clone an enrolled device. A pairing
code is useful only for bootstrap; it is not an ongoing device credential.

ADR-0002 requires authenticated sessions, sequence numbers, nonces and
idempotency. ADR-0003 requires the broker to deliver a short-lived exact-action
authorization directly to an enrolled Android PEP. This ADR selects the device
identity, TLS, enrollment, replay, revocation and key-lifecycle profile needed
to satisfy those decisions.

The Android implementation baseline is API 30. Android Keystore keeps key
material non-exportable and can bind it to a TEE or StrongBox. Android's own
guidance says StrongBox is more isolated but not universally available and is
not necessary for every application. The Runtime therefore prefers StrongBox,
accepts TEE-backed P-256 keys, and reports rather than invents the effective
security level.

## Decision

### 1. Transport boundary

All enrollment and device traffic uses TLS. Release builds contain no
plaintext device endpoint, cleartext fallback, trust-all verifier or debug CA
override.

- Enrollment uses HTTPS at the closed path `/v0/enroll`.
- Normal request/event transport may use HTTPS or WSS after protocol framing is
  decided by HMR-103.
- TLS 1.3 is preferred. TLS 1.2 is the only allowed fallback for the API-30
  compatibility baseline. TLS 1.1 and earlier are rejected.
- Hostname/IP identity is verified. Redirects are not followed across origins,
  schemes or ports.
- TLS early data / 0-RTT is disabled for enrollment, authorization and action
  messages because application replay defense must run before dispatch.
- Cipher suites are the intersection of the provider's enabled defaults and a
  small reviewed AEAD profile: TLS 1.3 AES-GCM/ChaCha20 suites and TLS 1.2
  ECDHE with AES-GCM/ChaCha20. CBC and static-RSA key exchange are removed.

This follows [RFC 9325](https://www.rfc-editor.org/rfc/rfc9325), which prefers
TLS 1.3 while retaining TLS 1.2 for application-protocol interoperability. The
manifest and [Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)
both deny cleartext, and the static configuration trusts only the Android
system store.

### 2. Separate key authorities

V0.1 uses ECDSA P-256 with SHA-256 because it is supported by the API-30
Android Keystore, TEE and StrongBox baseline. SHA-256 is used for SPKI,
certificate, nonce and canonical-action digests. Algorithm identifiers are
explicit and negotiated; unknown or weaker algorithms fail closed.

| Key / trust object | Owner | Purpose | Exportable? |
|---|---|---|---|
| Device identity signing key | Android Keystore | Stable per-install identity and enrollment proof | No |
| Device mTLS transport key | Android Keystore | TLS client authentication | No |
| Broker TLS key / CA set | Protected broker | Server authentication and device-client certificate issuance | No |
| Policy authorization signing key | Protected broker signer | Sign exact `ExecutionAuthorization` objects | No |
| Confirmation key | Trusted presenter / Android Keystore | User-presence receipts | No |
| Audit integrity key | Trusted audit writer | Audit chain | No |

Keys are not reused across these purposes. The device identity key does not
sign arbitrary action data and the policy signer is not exposed as a generic
sign-bytes API.

The stable `device_id` is `hmr_` plus unpadded base64url SHA-256 of the device
identity SubjectPublicKeyInfo. A user-visible device name is mutable metadata
and never an authorization identity.

### 3. Android identity creation

The bridge creates the identity key with `AndroidKeyStore`, curve
`secp256r1`, signing purposes only and SHA-256 digest authorization.

1. Prefer StrongBox only when the device advertises it.
2. If StrongBox rejects this supported key profile, retry in ordinary Android
   Keystore and record the actual `KeyInfo.securityLevel`.
3. Production enrollment requires `TRUSTED_ENVIRONMENT` or `STRONGBOX` by
   default. A future explicit compatibility policy may permit a software-backed
   key, but it cannot silently claim hardware protection.
4. The private key is never serialized, backed up or returned through the
   protocol.
5. Keystore generation and signing run off the Android main thread when wired
   into the enrollment UI.

When a broker supplies an attestation challenge, the challenge is 16–128
random bytes and is bound at key creation. The complete certificate chain is
validated by the broker, not by the same Android device. Validation includes
the chain, trusted attestation root, revocation status, challenge and expected
key authorizations, following Android's
[key-attestation guidance](https://developer.android.com/privacy-and-security/security-key-attestation).
Attestation is an assurance input, not a replacement for proof of possession,
TLS or user-authorized enrollment.

### 4. Enrollment ceremony

Enrollment is an explicit user action on both a trusted broker surface and the
Android app.

1. Broker creates a cryptographically random, single-use enrollment token with
   at least 128 bits of entropy, a broker challenge and a ten-minute maximum
   lifetime.
2. The trusted broker surface displays a QR payload containing the HTTPS
   endpoint, token, challenge, broker identity and bootstrap trust mode. The
   token is never placed in a URL, log or crash report.
3. Android displays the broker identity and asks the user to approve pairing.
4. Android creates/loads its identity and proves possession by signing the
   closed enrollment transcript, including endpoint, broker challenge, device
   SPKI, requested protocol range and a client nonce.
5. Broker atomically consumes the token, validates TLS/bootstrap trust,
   transcript, proof, protocol range and optional attestation, then records the
   device as enrolled.
6. Broker issues a client certificate for a separate Android Keystore mTLS key
   and returns the broker trust set, policy-verification keys, device record
   version and revocation handle.
7. Android verifies the full response transcript and establishes a fresh mTLS
   session. Enrollment is incomplete until this authenticated round trip
   succeeds.

Two bootstrap trust modes are allowed:

- `PUBLIC_PKI`: normal Android system trust plus endpoint identity validation.
- `OUT_OF_BAND_SPKI`: the trusted QR carries current and backup SHA-256 SPKI
  pins for a private broker. The enrollment TLS verifier accepts only those
  pins and the exact displayed endpoint. This verifier is scoped to the one
  enrollment ceremony and cannot become a process-wide trust-all manager.

The out-of-band mode is not enabled until invalid-chain, wrong-pin, missing
backup-pin and hostname/endpoint-substitution tests pass. Static user-added CAs
and debug trust overrides remain forbidden in release builds. Android's
[pinning guidance](https://developer.android.com/privacy-and-security/security-config#CertificatePinning)
requires a backup key to avoid an unrecoverable rotation failure.

### 5. Authenticated session

After enrollment, every connection uses mTLS. The broker validates the active
device record and client certificate; Android validates the broker chain,
endpoint identity and enrolled trust-set version. A valid TLS connection proves
peer identity but does not authorize a phone action.

The application handshake binds:

- device and broker ids;
- random 128-bit-or-stronger `session_id`;
- negotiated protocol version and schema bundle digest;
- client/server nonces and TLS exporter/channel-binding value when supported;
- active device-certificate, broker-trust-set and policy-key versions;
- issue time, expiry and first sequence number.

Sessions last at most 24 hours, use strictly increasing sequence numbers
starting at one and are invalidated by certificate/key rotation, unlink,
revocation or protocol-policy change. Reconnect establishes a new session; it
does not continue an unverified sequence stream.

### 6. Replay defense and pre-dispatch persistence

TLS record ordering is not treated as application exactly-once delivery.
Every protected device message carries its session id, positive sequence,
16–64-byte random nonce, issue time and expiry. An execution authorization has
a maximum lifetime of 30 seconds.

Immediately before capability dispatch, Android PEP performs one atomic replay
transaction:

1. validate the enrolled device/session, authorization signature and exact
   action digest;
2. reject invalid time bounds, expired messages and excessive future skew;
3. require `sequence == highest_sequence + 1` for the session;
4. reject a previously recorded nonce digest;
5. persist the new sequence and nonce digest in no-backup storage;
6. dispatch only after the persistence commit succeeds.

Corrupt, unreadable, full or unwritable replay state fails closed with
`STATE_UNAVAILABLE`/`AUTHORIZATION_INVALID`; it is never reset automatically.
The V0.1 Android ledger holds at most eight live sessions and 1,024 consumed
nonces per session. Capacity exhaustion requires session rotation or explicit
recovery; it does not evict a live security record.

Nonce digests and the highest sequence remain until session expiry. The later
idempotency/result cache required by ADR-0002 may retain terminal results for a
longer ADR-0006 window, but cannot be shorter than this transport replay window.
Duplicate delivery returns `REPLAY_DETECTED` or the already-recorded terminal
result through the dedicated result-query path; it never executes again.

### 7. Key and certificate lifecycle

- mTLS sessions: maximum 24 hours.
- mTLS client certificates: maximum 30 days; renew before two-thirds of their
  lifetime.
- mTLS transport key: rotate at least every 90 days and on suspected exposure.
- Device identity key: rollover at least annually, or immediately after
  compromise, using an authenticated old-key/new-key proof. Losing the old key
  requires fresh user enrollment.
- Broker TLS/CA and policy-verification keys: publish current and next keys in
  a versioned trust set before activation. Remove the old key only after the
  bounded overlap and all active sessions have expired.

Rotation never lowers the TLS/protocol floor, silently changes a device id or
accepts an unsigned trust set. Certificate expiry or unknown key version fails
closed and produces an actionable enrollment/renewal status.

### 8. Revocation, unlink and lost-device response

The broker maintains device status independently of an active device session.
Revocation immediately blocks new TLS sessions and authorization issuance,
invalidates active sessions and records a security audit event.

Local unlink deletes device identity and transport aliases, certificates,
broker trust state, sessions and replay ledger. Remote revoke does not depend
on the compromised planner or device acknowledging the request. Re-enrollment
after unlink/revoke creates a new identity and device id unless an explicitly
audited recovery flow proves continuity.

### 9. Storage and backup

Identity/transport private keys live only in Android Keystore. Certificates,
trust-set metadata and replay state live in app-private no-backup storage.
Android cloud backup and device-to-device transfer exclude all app state; a
restored APK therefore starts unenrolled and cannot impersonate the original
device. This is verified against Android's
[backup exclusion rules](https://developer.android.com/identity/data/autobackup).

The replay ledger contains opaque ids, sequence numbers, nonce digests and
expiries only. Enrollment tokens, private keys, action authorizations, raw
screens and user content are never stored in it.

### 10. Safe errors and audit

Stable security outcomes include:

- `AUTHENTICATION_FAILED`
- `PROTOCOL_INCOMPATIBLE`
- `AUTHORIZATION_INVALID`
- `AUTHORIZATION_EXPIRED`
- `REPLAY_DETECTED`
- `DEVICE_REVOKED`
- `DEVICE_IDENTITY_UNAVAILABLE`
- `REPLAY_STATE_UNAVAILABLE`
- `TLS_POLICY_VIOLATION`

Audit records endpoint class (not credentials), device/session ids, certificate
and key versions, protocol/schema version, decision code and safe timing. They
never record pairing tokens, private material, full certificates, raw nonces,
authorization blobs or TLS exporter values.

### 11. Development and test isolation

Test fixtures use a separate package/build variant, trust root and device-id
namespace. They cannot enroll into production brokers. Release builds contain
no flag that disables TLS, hostname verification, mTLS, replay persistence or
revocation checks. A debug build may use a synthetic local CA only when the CA
is compiled into that isolated variant and the UI/audit visibly marks it as
test-only.

## Rejected alternatives

### Plain HTTP on localhost or LAN

Rejected because Android and broker may be on different hosts and local
networks are not a trust boundary.

### Pairing code as permanent authentication

Rejected because human-sized codes are replayable and provide no key rotation,
device binding or secure lost-device response.

### One shared bearer token for every device

Rejected because compromise cannot be isolated or independently revoked and a
backup can clone the credential.

### Trust-all certificate manager or disabled hostname verifier

Rejected because it converts TLS into encryption without peer authentication.

### Static single certificate pin

Rejected because loss/rotation of that key bricks enrollment. Approved
out-of-band pinning always includes a backup pin and exact endpoint binding.

### In-memory replay cache only

Rejected because process death, reboot or reconnect would reset the device into
an allow state.

### Use the identity key for transport, policy and confirmation

Rejected because one compromise or lifecycle event would collapse independent
authorities and make rotation unsafe.

## Consequences

### Positive

- A copied app backup cannot clone device identity.
- Network attackers cannot downgrade to plaintext or replay an action after
  reconnect/restart.
- One device can be revoked without rotating every device credential.
- Keystore security level and optional attestation are explicit evidence.
- Broker, transport and authorization keys rotate independently.

### Costs

- Enrollment needs a trusted UI ceremony and broker certificate service.
- Android and broker need certificate/trust-set rotation and revocation state.
- Strict sequence ordering requires a new session after unrecoverable gaps.
- Persistent replay state adds a fail-closed availability dependency.
- Private self-hosted brokers require carefully tested out-of-band pinning.

## Required verification

Before a phone capability is enabled:

1. HTTP, TLS 1.1, wrong hostname, invalid chain, wrong pin and redirect tests
   fail closed.
2. A production build contains no debug CA/trust override and no cleartext
   route.
3. Device identity private material cannot be exported and backup/restore does
   not preserve enrollment.
4. StrongBox fallback reports the actual TEE/software level.
5. Invalid attestation root, revoked chain, wrong challenge and altered SPKI
   fail broker validation.
6. Replayed nonce/sequence, sequence gap, expired authorization and future time
   fail before dispatch.
7. Replay state survives Android process restart; corrupt/full/unwritable state
   denies execution.
8. Duplicate/reorder/disconnect executes an identical action at most once.
9. Revoked device and old trust/key versions cannot reconnect or execute.
10. Logs and artifacts contain no enrollment token, private key, authorization
    blob, raw nonce or TLS exporter value.

## Implementation boundary for HMR-102

HMR-102 implements and tests:

- Android Keystore P-256 identity creation, description, proof signing and
  deletion;
- the closed HTTPS enrollment endpoint and TLS 1.2/1.3 floor;
- a crash-safe no-backup replay ledger and strict pre-dispatch state machine;
- manifest/network/backup policy checks;
- no phone capability, transport listener or raw command endpoint.

HMR-103 owns closed enrollment/protocol schemas, codecs, compatibility
negotiation and cross-language golden fixtures. HMR-104 owns the only dispatch
path. HMR-105 is the first capability consumer.
