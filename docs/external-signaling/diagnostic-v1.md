# NXS connectivity diagnostics

An opt-in diagnostic establishes ICE, pinned DTLS and SCTP on the gameplay UDP
socket, exchanges one reliable PING/PONG, then closes. Its `NXD1` admission is
separate from `NXS1` player admission and never creates a player child or login
pipeline. Ordinary NXS registration and heartbeat install the host's diagnostic
context; assisted checks use the existing WebSocket exchange.

## Attempt and authorization

The caller authorizes one immutable attempt: provider/host context, candidate
revision, family, target, expected host fingerprint, profile and absolute expiry.
Generate its ID from 16 random bytes. Attempts last at most 60 seconds, with
15 seconds for gathering, signing, signaling and channel establishment. Wall and
monotonic deadlines prevent retries or clock rollback from extending expiry.

The provider validates workload and target authorization, the complete offer and
its detached assertion before issuing a permit and signed answer. Parent
authority and key retirement must cover the original deadline. The prober gets
only per-attempt credentials; reusable host admission secrets and provider
signing keys remain with their owners. Keep SDP, credentials and assertions out
of logs. Numeric address validation alone does not authorize a target.

## Context and admission bytes

Integers are unsigned big endian; JavaScript-visible 64-bit values are positive
safe integers, at most `9007199254740991`. Hex is lowercase and base64 uses
`A-Z a-z 0-9 + /`, without padding and with canonical unused bits.

Context contains an HTTPS provider origin (the [canonical-origin
profile](control-v1.md#canonical-origins), at most 256 bytes), host ID matching
`[A-Za-z0-9_-]{1,128}`, 16-byte incarnation and generation. Its encoding is:

```text
UTF8("nxs-diagnostic-context-v1\0")
|| uint16(length(originUTF8)) || originUTF8
|| uint16(length(hostIdUTF8)) || hostIdUTF8
|| incarnation16 || uint64(generation)
```

`D` is SHA-256 of these bytes. Context comes from trusted host configuration;
an incoming permit cannot select it. Reconnecting control or replacing candidates
does not rotate the gameplay incarnation.

The ICE username is `NXD1` + four uppercase alphanumeric key-ID characters +
base64(nonce12 + ciphertext + GCM-tag16). The encrypted plaintext is:

| Offset | Bytes | Value |
| --- | ---: | --- |
| 0 | 4 | Absolute expiry, whole Unix seconds |
| 4 | 32 | Client DTLS SHA-256 fingerprint |
| 36 | 16 | Secret HMAC binding of canonical ephemeral P-384 key |
| 52 | 16 | Random attempt ID |
| 68 | 32 | SHA-256 of exact complete offer bytes |
| 100 | 8 | Candidate revision |
| 108 | 1 | Profile/family: `0x01` direct IPv4, `0x81` direct IPv6, `0x02` assisted IPv4, `0x82` assisted IPv6 |
| 109 | 16 | Numeric target address; IPv4 has twelve zero bytes followed by four address bytes |
| 125 | 2 | Target UDP port; assisted profile uses zero address and port |
| 127 | 1 | Client ICE password byte length |
| 128 | 22–30 | Client ICE password |

Compare endpoints by family, packed address and port. Reject mapped IPv6, zone
IDs, hostname lookup and ambiguous IPv4 decimals. Username length is
`8 + ceil((156 + passwordBytes) * 4 / 3)`: passwords of 22, 24 and 30 bytes produce
246, 248 and 256 characters. Passwords need at least 128 random bits. This bound
belongs to diagnostics; NXS1 player passwords retain their existing limits.

Admission secrets are valid UTF-8, 32–256 encoded bytes, used directly rather than
base64-decoded. AES-256-GCM uses a fresh random 12-byte nonce and 128-bit tag.
With `H` = HMAC-SHA256 under that secret:

```text
AES key = H(UTF8("nxs-diagnostic-aead-v1\0") || D)
GCM AAD = UTF8("nxs-diagnostic-admission-v1\0") || header8 || 0x00 || D || 0x00 || remoteUfragASCII
server ICE password = base64(first24(H(UTF8("nxs-diagnostic-ice-v1\0") || D || fullLocalUfragASCII)))
key binding = first16(H(UTF8("nxs-diagnostic-identity-v1\0") || D || canonicalSPKI))
```

The canonical SPKI is DER prefix `3076301006072a8648ce3d020106052b81040022036200`
followed by the validated 97-byte uncompressed P-384 point. Detached offer
assertions use ES384, with a 96-byte IEEE-P1363 `r || s` signature over:

```text
UTF8("nxs-diagnostic-assertion-v1\0") || D
|| complete plaintext with its 16-byte key-binding slot zeroed
|| uint16(remoteUfragASCII.length) || remoteUfragASCII
```

`remoteUfrag` is 4–256 ICE characters. The issuer verifies the exact offer and
assertion before minting; the assisted host also verifies the forwarded assertion.
Direct admission relies on the issuer's attestation, authenticated first-STUN
credentials and the client DTLS pin. The ephemeral assertion key is separate from
Minecraft identity. Diagnostic domains prevent interchange with player tokens.

## Profiles

Both profiles use UDP, one bundled application/mid 0, full ICE, offer setup
`actpass`, answer setup `active`, SCTP port 5000 and maximum message size 262144.
Gathering finishes before the single offer; no later trickle updates are allowed.
SDP is at most 16,384 UTF-8 bytes, with one numeric candidate of the attempt's
family and valid media/candidate ports. Bounded `generation`, `network-id`,
`network-cost` and matching `ufrag` candidate extensions are accepted.
`ice-options:trickle` capability alone does not mean gathering is incomplete.
Reject ICE-lite, relay/TCP and incompatible or ambiguous transport attributes.

**Profile 1 (direct)** binds one exact target address, port and candidate revision.
The probe gathers on a dedicated socket without external STUN/TURN. The host
receives no per-attempt offer or command before the client's first STUN packet;
it reconstructs remote SDP from the authenticated observed source. The host
compares the permit's target with its current eligible candidates on that listener.
The prober binds a numeric same-family address and port; its selected local tuple
and remote destination must match that bind and the signed target. Production
probe targets must be public; the native test constructor also permits loopback.

**Profile 2 (assisted)** signs a zero target address/port, original revision and
explicit family. The provider forwards the exact offer and assertion using
`assisted-join` with `purpose: "connectivity-check"`. Replace player `networkId`
and `cpk` with `assertion: {publicPointHex, signatureBase64}`; the point is the
97-byte key above and the signature is its 96-byte P1363 proof. Other host context,
credential and expiry fields follow the [WebSocket envelope](control-v1.md#assisted-joins).
The host must have locally enabled assistance for this family. Incoming stateless
admission cannot enable profile 2.

An assisted offer has one host or server-reflexive candidate. A private IPv4 host
candidate is permitted: the host removes it from native remote SDP and waits for
authenticated incoming ICE to learn the public source. It never dials that private
address. Private server-reflexive and private IPv6 candidates are rejected.
Optional mapping discovery uses a numeric same-family provider-advertised STUN
server on the attempt's own socket and stops with the attempt.

The assisted answer contains one fresh public same-family host or server-reflexive
candidate. If needed, the host gathers it on the gameplay socket for this attempt.
The prober validates public scope before installing the signed answer; selected
pairs may use authenticated same-family public peer-reflexive mappings. A private
IPv4 prober initiates toward the public host candidate. This can still fail when
the host's NAT requires reciprocal traffic toward a public prober candidate.

## Provider-signed answer

The expected host fingerprint comes from the authorized job. The provider signs
the exact answer bytes in this compact ASCII JSON field order:

```json
{"version":1,"kind":"diagnostic-answer","keyId":"provider-key-id","expiresAt":1788484830000,"requestDigestHex":"...","answerSdpBase64":"...","answerDigestHex":"...","signatureBase64":"..."}
```

`expiresAt` is the original attempt deadline in milliseconds. `requestDigestHex`
is SHA-256 of the detached assertion transcript; `answerDigestHex` is SHA-256 of
the exact SDP bytes. Digests are 64 lowercase hex digits. Key IDs match
`[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}`. Base64 is canonical and unpadded; the ES384
signature is 96-byte P1363. The signed transcript is:

```text
UTF8("nxs-diagnostic-answer-v1\0")
|| uint16(keyIdASCII.length) || keyIdASCII
|| uint64(fixedExpiresAtMillis)
|| requestDigest32 || answerDigest32 || uint32(exactSdpByteLength)
```

Reject noncanonical JSON (including reordered/duplicate/unknown fields, whitespace,
escaped aliases or numeric alternatives), nested values and bodies over 24,576
bytes. Verify the signature, exact request/answer hashes, expected host DTLS pin
and profile before applying SDP. The answer has one completed numeric host/srflx
candidate, NXD1 username and 32-character ICE password. Direct answers match the
original target; assisted answers supply the fresh same-family candidate.

The caller supplies a trusted local catalog: provider origin, `notBefore`,
`expiresAt` and one to eight unique keys. Each key has family `provider-diagnostic`,
ID, canonical P-384 public point, `validFrom` and `validUntil`. The origin must
match the context, and catalog/key validity must cover the full attempt. The
signer self-verifies against that key. Before delivery, re-read the catalog and
require the same selected key material/window and sufficient parent validity.
Unrelated key changes are harmless. Never derive catalog trust from answer fields.

Java's `VerifiedDiagnosticAnswer.takeSdp()` supplies an owned copy once, after
rechecking catalog/key, cancellation, wall and monotonic expiry. Apply it
immediately. Closing, reentrant consumption or changed authority prevents release;
the transport continues enforcing the deadline and expected host fingerprint.
Catalog readers must be bounded synchronous local reads.

## Single PING/PONG and cleanup

The offering peer creates exactly `ReliableDataChannel` (ordered/reliable) and
`UnreliableDataChannel` (unordered, maxRetransmits 0), both with empty protocol and
zero lifetime override. Validate labels, uniqueness, reliability, selected endpoint
and pinned identity before exchanging application data. Both channels must open;
the unreliable channel carries no application frames.

The prober sends one fresh random 32-byte nonce on the reliable channel. The host
echoes it once. Each frame is exactly 56 bytes:

| Offset | Bytes | Value |
| --- | ---: | --- |
| 0 | 1 | NetherNet marker 0 |
| 1 | 4 | ASCII `NXDP` |
| 5 | 1 | Version 1 |
| 6 | 1 | 2 PING or 3 PONG |
| 7 | 1 | 0 reliable |
| 8 | 16 | Original attempt ID |
| 24 | 32 | PING nonce or exact echo |

Only the prober reports success, after verifying the PONG's attempt/nonce and
completing native cleanup. It then closes immediately. The host records PONG
submission and closes on disconnect, with a one-second fallback. Wrong kinds,
attempts, nonces, duplicates or unreliable-channel frames fail the check.

The local diagnostic policy bounds context, keys, endpoints/revisions, assisted
families and deadlines. Withdrawal, revocation, cancellation or expiry closes
attempts without renewing them. Four active diagnostics share native capacity
with players; sixteen used context/attempt IDs remain through their original
expiry, including across policy refresh or control reconnect. Reminting a token
with a new nonce cannot allocate the same attempt twice. Bad initial STUN
integrity neither allocates a peer nor consumes a valid permit. `NXD1` never falls
through to the player validator, even when disabled or malformed.

`NativeDiagnosticProbeAttempt` runs once on a caller-owned bounded worker. Native
close starts immediately on cancellation; cleanup waits up to five seconds. If
`cleanupComplete` is false, retain the capacity reservation until `termination()`
settles. A blocked signaling callback still occupies its worker. Host cleanup
failure similarly retains capacity and makes the gate unavailable.

Results retain the verified answer destination as `attemptedRemote` even on
transport failure; `selectedLocal`/`selectedRemote` describe an established pair.
Distinguish target failure from stale authorization, unavailable checker capacity
and cleanup failure. Host observations are bounded to 32 with a dropped count.
A first-contact claim additionally requires independent source-history evidence;
IPv4 and IPv6 are separate observations.

## Schemas and fixtures

[Admission schema](diagnostic-v1.schema.json) and
[answer schema](diagnostic-answer-v1.schema.json) describe structured values;
runtime checks enforce cryptographic, canonical-byte and transport constraints.
Shared public [admission](diagnostic-v1.fixtures.json),
[answer](diagnostic-answer-v1.fixtures.json) and
[PING/PONG](diagnostic-exchange-v1.fixtures.json) vectors cover wire interoperability.
Their secrets, private keys and nonces are test data only. Java and Node independently
verify the cryptographic fixtures. `:external-signaling:nativeAdmissionTest`
exercises local native admission, exchange and teardown; it does not establish
regional reachability or stock-client gameplay.
