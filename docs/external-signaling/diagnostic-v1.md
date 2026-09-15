# Draft NXS diagnostic admission v1

This is an opt-in codec specification, derived from the existing NXS1 compact admission design. It does not change NXS1 player bytes, Minecraft CPK verification, discovery or public routes. The new implementation has no native listener, workload authentication, game pipeline or regional executor. Provider-signed diagnostic answer creation and verification is a required next integration gate.

Network owns the neutral Java primitives under `signaling/diagnostic`. The matching TypeScript primitives are available only through `@warden/protocol/diagnostic-v1`; neither default package index advertises them. The original NXS1 implementation and provenance remain intact. Initial source baselines: Network `e10d6e3d4e926ed4c090979798a8c07adc41cc3f`; Warden `76636efea70c30ce98acd0b5d72530dbee1c3811`. Native Minecraft-profile/Chromium fixtures are supporting interoperability evidence, not signed diagnostic admission or gameplay evidence.

## Trust and fixed attempt

Before any offer, an authenticated workload claims an authorized job whose immutable target, region, host incarnation/generation, candidate revision, attempt ID, bounds profile and absolute expiry are fixed. Generate an attempt ID from 16 random bytes (128 bits), not a UUIDv4 with 122 random bits. Duplicate claims/results need durable idempotency and bounded retry traffic. Registration is not ownership of a target IP: the facade and executor still need target authorization, source identity and SSRF/routing policy. This cryptographic codec performs numeric syntax and family checks only; documentation/private addresses remain usable in isolated tests.

The prober completes gathering on a dedicated UDP socket with no external STUN/TURN, validates the actual offer profile and signs the detached assertion with a fresh P-384 key. Its workload identity and lease must be verified by the facade. `issue` additionally checks the exact offer and the detached signature before minting. It refuses issuance if the fixed expiry is no longer covered by the parent authority or key retirement; it never silently shortens or extends the signed expiry. Wall and monotonic deadlines bound crypto completion. The ephemeral assertion key is unrelated to a Minecraft identity or host admission key.

A stateless attempt sends no offer/candidates or per-attempt peer command to the host before the first incoming UDP admission packet. The facade can mint from the authorized background host profile. A future provider-signed answer must bind its exact SDP bytes/digest, pinned host DTLS fingerprint, offer digest, attempt, context, endpoint/revision/family, profile and expiry. The prober must verify that answer before applying remote SDP/starting transport. Existing player answer assertions bind fingerprints but do not supply this entire diagnostic response contract. A Warden-owned provider signer can attest the approved host identity; no provider service-account private key belongs on a game server or prober.

## Canonical context and compact carrier

All integers are unsigned, big endian. JavaScript-visible 64-bit values must be positive safe integers, at most `9007199254740991`. All hex is lowercase; ICE text uses ASCII `A-Z a-z 0-9 + /`, without padding.

The installed context is `(canonical HTTPS provider origin, opaque ASCII host ID, incarnation16, generation8)`. Host IDs match `[A-Za-z0-9_-]{1,128}`. Origin length is at most256 characters. Canonical context bytes are:

```
UTF8("nxs-diagnostic-context-v1\0")
|| uint16(length(originUTF8)) || originUTF8
|| uint16(length(hostIdUTF8)) || hostIdUTF8
|| incarnation16 || uint64(generation)
```

`contextDigest` is the complete SHA-256 of these bytes. Incarnation/generation are installed trusted context, not selected by an incoming permit. Candidate remaps, control reconnects or writer/key rotation must not rotate a live gameplay incarnation. A new canonical generation/context rejects old new-admission attempts without tearing down established player peers.

The carrier is `NXD1` + four uppercase alphanumeric key-ID characters + unpadded base64(nonce12 + ciphertext + GCM-tag16). It is an authenticated encrypted capability, matching NXS1's symmetric admission strength; it is not an asymmetric signature carried in ICE. Detached offer and future answer signatures supply asymmetric proofs. All derivations have diagnostic-only domains; an NXS1 or WDA2 token cannot select diagnostics and NXD1 cannot select player admission.

| Plaintext offset | Bytes | Value |
| --- | ---: | --- |
| 0 | 4 | Absolute expiry, whole Unix seconds |
| 4 | 32 | Client DTLS SHA-256 certificate fingerprint |
| 36 | 16 | Secret HMAC binding of canonical ephemeral P-384 key |
| 52 | 16 | Random attempt ID |
| 68 | 32 | SHA-256 of the exact complete offer bytes |
| 100 | 8 | Candidate revision |
| 108 | 1 | `0x01` IPv4/profile1 or `0x81` IPv6/profile1; all other values rejected |
| 109 | 16 | Numeric target address; IPv4 is twelve zero octets plus its four address octets |
| 125 | 2 | Target UDP port |
| 127 | 1 | Client ICE password byte length |
| 128 | 22..30 | Client ICE password |

IPv6 uses all16 octets; IPv4-mapped IPv6, zone IDs, hostname lookup and ambiguous IPv4 decimal representations are rejected. Endpoint equality uses family + packed octets + port. The host behind NAT cannot observe its public destination directly: it must compare the authenticated endpoint/revision to its current eligible candidate set on this exact gameplay listener; the prober separately verifies its actual selected remote tuple/family. A stale revision result never updates a newer candidate's readiness.

The length is `8 + ceil((156 + passwordBytes) * 4 / 3)`. Password lengths22,24,30 produce246,248,256 characters;31 would produce258 and is rejected. This limitation applies only to the new controlled prober profile. NXS1 player passwords remain22..91. Measured Chromium151 offers use24. Do not rewrite an incompatible offer or assume that every libwebrtc API accepts arbitrary ICE credentials. ICE passwords require at least128 bits of random generator output; callers own secure generation. [RFC8445 section5.3](https://www.rfc-editor.org/rfc/rfc8445.html#section-5.3)

## Cryptography and detached proof

Secrets are valid UTF-8,32..256 encoded bytes; they are not base64-decoded. AES-256-GCM uses a fresh cryptographically random12-byte nonce and128-bit tag. Let `H` be HMAC-SHA256 under the admission secret, and `D` the context digest:

```
AES key = H(UTF8("nxs-diagnostic-aead-v1\0") || D)
GCM AAD = UTF8("nxs-diagnostic-admission-v1\0") || header8 || 0x00 || D || 0x00 || remoteUfragASCII
server ICE password = base64(first24(H(UTF8("nxs-diagnostic-ice-v1\0") || D || fullLocalUfragASCII)))
key binding = first16(H(UTF8("nxs-diagnostic-identity-v1\0") || D || canonicalSPKI))
```

The canonical public key is the validated97-byte uncompressed P-384 point prefixed with DER hex `3076301006072a8648ce3d020106052b81040022036200`. Detached signatures use ES384 with fixed96-byte IEEE-P1363 `r || s`, never variable DER. The signed transcript is:

```
UTF8("nxs-diagnostic-assertion-v1\0") || D
|| complete encoded plaintext, with the16-byte key-binding slot set to zero
|| uint16(remoteUfragASCII.length) || remoteUfragASCII
```

This covers exact offer hash, target, generation/context, family, attempt, fixed expiry and closed bounds profile. The host reconstructs these bytes from authenticated claims/context and verifies the same signature as the facade. It does not claim to reconstruct the gathered offer from the minimal stateless ICE description. The facade validates that the complete offer really matches the carried transport fields/hash; the host relies on that issuer attestation and checks the actual pinned DTLS peer. There is no unused offer hash or fabricated Minecraft verifier.

The proof establishes possession when the assertion was signed, with possession of the separately pinned DTLS private key established by transport. It is not a fresh host-nonce proof of the assertion key. Captured public assertions alone cannot pass DTLS as another certificate.

## Profile1 and host authentication frame

Profile1 is immutable: SCTP port5000, SDP maximum message size262144, UDP, one bundled application/mid0, offer setup `actpass`, one selected numeric host candidate of the job family, full gathering before the single offer, no trickle updates. Normal candidate extensions `generation`, `network-id`, `network-cost` and matching `ufrag` are bounded and accepted. `ice-options:trickle` capability advertisement alone does not imply trickle execution; only a complete offer is accepted, and the adapter must prohibit later updates. SDP cannot prove the actual gathering state or socket binding; the prober must enforce both through its API. Never rewrite incompatible SCTP values to fit. Channel/profile settings follow the published Minecraft guide. [Mojang transport profile](https://mojang.github.io/bedrock-protocol-docs/guides/nether-net-onboarding-guide/#6-webrtc-peerconnection-configuration)

The offering peer creates exactly `ReliableDataChannel` (ordered/reliable) and `UnreliableDataChannel` (unordered, maxRetransmits0). Negotiated labels, uniqueness and reliability must be checked before AUTH. The maximum SCTP message262144 remains distinct from the much smaller application-frame policy.

After pinned DTLS and both channels, the prober sends a single217-byte AUTH on the reliable channel:

```
0x00 NetherNet unfragmented header
"NXDP" four ASCII bytes
version=1, kind=1(AUTH), channel=0(reliable)
attempt16 || uncompressedPublicPoint97 || ES384signature96
```

The host validates exact frame length/header/attempt, secret key binding and detached signature once before any diagnostic challenge. Wrong key/signature, duplicated AUTH, expiry, cancellation or key revocation cannot produce a diagnostic principal. `VerifiedDiagnosticAdmission` is a pending verification lease, with a monotonic deadline independent of wall-clock rollback; close it on revocation/cancellation. `DiagnosticPrincipal` has no player network ID, CPK verifier, player admission interface or conversion method. Neither type verifies socket state itself: that prerequisite remains the future host adapter's responsibility.

After AUTH, a future isolated handler must exchange independently generated random challenges from each endpoint on each of the two channels, bound to attempt/channel, with exact replies, then close. Channel-open or one-way echo is insufficient. Declared limits per endpoint:60s total attempt,15s handshake,1KiB sent application bytes,256-byte framed messages,12 frames, at most2 application retries on the unreliable channel. These are deliberately larger than512 application bytes to allow the217-byte assertion and bounded bidirectional retries. No Bedrock packet is sent. The challenge framing/state machine and execution limits are not implemented by this codec slice.

## Required integration gates

- Verify workload/lease/target authority before issuance; fixed expiry must be covered at mint completion. Publish no reusable admission secret to a prober. Keep offers, ICE passwords, assertions and permit responses out of ordinary logs.
- Add signed answer verification before applying remote SDP. A configured certificate string alone is not verified transport identity.
- Branch on a closed verified diagnostic principal before constructing/emitting `AdmittedNetherNetChildChannel` or installing any `TransportIdentityBinding`, `AdmissionPrincipal`, Geyser login/game/outcome/accounting pipeline. No always-true `IdentityKeyVerifier` and no `acceptForwardedIdentity` bypass.
- Share native session/pending/replay and actual teardown accounting with players; add a small lower-priority diagnostic quota (initial target at most2 concurrent per host). Disabled/saturated checker capacity is unavailable/unknown, not a false reachability failure. Retain reservations until native teardown really completes.
- Replay identity is context + attempt, not merely a hash of ciphertext: reminting with another nonce must not allocate another peer for the same retained attempt. The issuer fixes one attempt expiry; keep bounded replay metadata through that deadline. Controller reconnect does not clear it.
- Actual numeric destination/family/source allowlisting and hard256 UDP-send/1200-byte UDP-payload caps require native send-path support on both roles. A Java process timeout or a declared manifest is not enforcement. Default native MTU1280 can allow1232-byte UDP payloads, so1200 must not be claimed without explicit configuration and counted send-path tests. Do not cap the shared gameplay socket globally or break its independent STUN monitor.
- Require positive authenticated transport plus all four challenge directions before qualification. Preserve obsolete results/selected-path evidence without updating current readiness. Distinguish target failure, checker failure, stale state and quota exhaustion.
- Run fresh first-contact checks before any host traffic to that checker IP. A successful probe contaminates that source; another source port is insufficient. Two regions corroborate reachability but cannot prove acceptance from arbitrary sources.
- Add actual native NXD1 rejection/acceptance, zero game/login promotion, dual-stack exact selected-pair, wrong DTLS/channel/offer/authority, replay, revocation and cleanup tests. The present fixture tests prove codec interoperability only. Native/platform publication and real stock-client world-entry/gameplay gates remain separate.

The shared JSON contains public test secrets/private keys/nonces, explicitly marked never for deployment. Six deterministic vectors cover both families and password22/24/30. Fresh Java ES384 signatures are separately verified in Node/TypeScript, in addition to both implementations reproducing identical carrier/ICE-password/transcript/AUTH bytes.
