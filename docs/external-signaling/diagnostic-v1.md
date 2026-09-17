# Draft NXS diagnostic admission v1

This is an opt-in codec specification, derived from the existing NXS1 compact admission design. It does not change NXS1 player bytes, Minecraft CPK verification, discovery or public routes. The codec itself owns no native listener, workload authentication, game pipeline or regional executor. The separately staged, default-off listener consumer and its actual native send limits are specified in `diagnostic-host-v1.md`; that consumer has no public facade or regional executor. Standalone provider-signed answer creation/verification is specified in `diagnostic-answer-v1.md`; live wiring before transport remains a required integration gate.

Network owns the neutral Java primitives under `signaling/diagnostic`. The matching TypeScript primitives are available only through `@warden/protocol/diagnostic-v1`; neither default package index advertises them. The original NXS1 implementation and provenance remain intact. Initial source baselines: Network `e10d6e3d4e926ed4c090979798a8c07adc41cc3f`; Warden `76636efea70c30ce98acd0b5d72530dbee1c3811`. Native Minecraft-profile/Chromium fixtures are supporting interoperability evidence, not signed diagnostic admission or gameplay evidence.

## Trust and fixed attempt

Before any offer, an authenticated workload claims an authorized job whose immutable target, region, host incarnation/generation, candidate revision, attempt ID, bounds profile and absolute expiry are fixed. Generate an attempt ID from 16 random bytes (128 bits), not a UUIDv4 with 122 random bits. Duplicate claims/results need durable idempotency and bounded retry traffic. Registration is not ownership of a target IP: the facade and executor still need target authorization, source identity and SSRF/routing policy. This cryptographic codec performs numeric syntax and family checks only; documentation/private addresses remain usable in isolated tests.

For profile 1, the prober completes gathering on a dedicated UDP socket with no external STUN/TURN, validates the actual offer profile and signs the detached assertion with a fresh P-384 key. Its workload identity and lease must be verified by the facade. `issue` additionally checks the exact offer and the detached signature before minting. It refuses issuance if the fixed expiry is no longer covered by the parent authority or key retirement; it never silently shortens or extends the signed expiry. Wall and monotonic deadlines bound crypto completion; nonfinite or backward final monotonic readings are rejected. Input byte lengths are checked before copying offers, AUTH frames and nonces. The ephemeral assertion key is unrelated to a Minecraft identity or host admission key.

A stateless attempt sends no offer/candidates or per-attempt peer command to the host before the first incoming UDP admission packet. The facade can mint from the authorized background host profile. A provider-signed answer must bind its exact SDP bytes/digest, pinned host DTLS fingerprint, offer digest, attempt, context, endpoint/revision/family, profile and expiry. The prober must verify that answer before applying remote SDP/starting transport. Existing player answer assertions bind fingerprints but do not supply this entire diagnostic response contract. A Warden-owned provider signer can attest the approved host identity; no provider service-account private key belongs on a game server or prober.

## Canonical context and compact carrier

All integers are unsigned, big endian. JavaScript-visible 64-bit values must be positive safe integers, at most `9007199254740991`. All hex is lowercase; ICE text uses ASCII `A-Z a-z 0-9 + /`, without padding.

The installed context is `(canonical HTTPS provider origin, opaque ASCII host ID, incarnation16, generation8)`. Host IDs match `[A-Za-z0-9_-]{1,128}`. Origin length is at most256 characters. It uses the strict shared control-origin grammar, restricted to HTTPS: no normalization, punycode/trailing-dot relaxation, invalid ports or alternate IPv6 spellings. Java reuses ControlOrigin; the protocol-owned TypeScript grammar copy has explicit source provenance and checks all274 shared origin fixtures. Canonical context bytes are:

```
UTF8("nxs-diagnostic-context-v1\0")
|| uint16(length(originUTF8)) || originUTF8
|| uint16(length(hostIdUTF8)) || hostIdUTF8
|| incarnation16 || uint64(generation)
```

`contextDigest` is the complete SHA-256 of these bytes. Incarnation/generation are installed trusted context, not selected by an incoming permit. Candidate remaps, control reconnects or writer/key rotation must not rotate a live gameplay incarnation. A new canonical generation/context rejects old new-admission attempts without tearing down established player peers.

The carrier is `NXD1` + four uppercase alphanumeric key-ID characters + unpadded base64(nonce12 + ciphertext + GCM-tag16). It is an authenticated encrypted capability, matching NXS1's symmetric admission strength; it is not an asymmetric signature carried in ICE. Detached offer and answer signatures supply asymmetric proofs. All derivations have diagnostic-only domains; an NXS1 or WDA2 token cannot select diagnostics and NXD1 cannot select player admission.

| Plaintext offset | Bytes | Value |
| --- | ---: | --- |
| 0 | 4 | Absolute expiry, whole Unix seconds |
| 4 | 32 | Client DTLS SHA-256 certificate fingerprint |
| 36 | 16 | Secret HMAC binding of canonical ephemeral P-384 key |
| 52 | 16 | Random attempt ID |
| 68 | 32 | SHA-256 of the exact complete offer bytes |
| 100 | 8 | Candidate revision |
| 108 | 1 | Profile 1: `0x01` IPv4 / `0x81` IPv6. Profile 2: `0x02` IPv4 / `0x82` IPv6; all other values rejected |
| 109 | 16 | Numeric target address; IPv4 is twelve zero octets plus its four address octets |
| 125 | 2 | Target UDP port; profile 2 uses 0 with an all-zero target address |
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

## Direct and assisted profiles

Profile1 is immutable: SCTP port5000, SDP maximum message size262144, UDP, one bundled application/mid0, offer setup `actpass`, full ICE (ice-lite rejected), a media port1..65535, one selected numeric host candidate of the job family, full gathering before the single offer, no trickle updates. Normal candidate extensions `generation`, `network-id`, `network-cost` and matching `ufrag` are bounded and accepted. `ice-options:trickle` capability advertisement alone does not imply trickle execution; only a complete offer is accepted, and the adapter must prohibit later updates. SDP cannot prove the actual gathering state or socket binding; the prober must enforce both through its API. Never rewrite incompatible SCTP values to fit. Channel/profile settings follow the published Minecraft guide. [Mojang transport profile](https://mojang.github.io/bedrock-protocol-docs/guides/nether-net-onboarding-guide/#6-webrtc-peerconnection-configuration)

Profile 2 retains the same signed carrier, deadline, channel and AUTH requirements. It uses an all-zero target address and port 0, plus the original candidate revision and explicit family. The host must have locally enabled assistance for that family. The signed offer contains one numeric host or server-reflexive candidate. The probe may discover its public mapping using a provider-advertised STUN endpoint on its actual UDP mux; this bounded discovery stops with the attempt.

The provider forwards the exact offer and detached assertion over the existing authenticated `assisted-join` WebSocket exchange with `purpose: "connectivity-check"`. It carries the original token/password and host epoch fields, but no player CPK or network ID. The host verifies admission and proof before allocating a diagnostic peer and sends ICE toward the probe. Incoming stateless admission rejects profile 2. Its signed answer carries one fresh same-family public candidate. If the host has no public direct candidate, it gathers its mapping on the same socket for this attempt only. Both peers send ICE to open their NAT filters; the prober verifies public scope before applying the signed answer and checks selected family and pinned DTLS identity. This works without a surviving warm-STUN mapping. No player channel or gameplay pipeline is created.

The offering peer creates exactly `ReliableDataChannel` (ordered/reliable) and `UnreliableDataChannel` (unordered, maxRetransmits0). Negotiated labels, uniqueness and reliability must be checked before AUTH. The maximum SCTP message262144 remains distinct from the much smaller application-frame policy.

After pinned DTLS and both channels, the prober sends a single217-byte AUTH on the reliable channel:

```
0x00 NetherNet unfragmented header
"NXDP" four ASCII bytes
version=1, kind=1(AUTH), channel=0(reliable)
attempt16 || uncompressedPublicPoint97 || ES384signature96
```

The host validates exact frame length/header/attempt, secret key binding and detached signature once before any diagnostic PONG. Wrong key/signature, duplicated AUTH, expiry, cancellation or key revocation cannot produce a diagnostic principal. `VerifiedDiagnosticAdmission` is a pending verification lease, with a monotonic deadline independent of wall-clock rollback; close it on revocation/cancellation. `DiagnosticPrincipal` has no player network ID, CPK verifier, player admission interface or conversion method. Neither type verifies socket state itself: that prerequisite remains the separately opted-in host adapter's responsibility.

After AUTH, the prober may optionally request one random PING/PONG on the reliable channel. The host never initiates a challenge or completion exchange. A no-ping result records transport establishment and AUTH submission; it cannot claim remote AUTH verification. With ping enabled, qualification also requires the original PONG. Declared limits per endpoint:60s total attempt,15s handshake,1KiB sent application bytes,256-byte frames,12 frames; no application retry. No Bedrock packet is sent. Framing and native execution are described in `diagnostic-host-v1.md`.

## Integration requirements and remaining delivery gates

- Verify workload/lease/target authority before issuance; fixed expiry must be covered at mint completion. Publish no reusable admission secret to a prober. Keep offers, ICE passwords, assertions and permit responses out of ordinary logs.
- Add signed answer verification before applying remote SDP. A configured certificate string alone is not verified transport identity.
- Branch on a closed verified diagnostic principal before constructing/emitting `AdmittedNetherNetChildChannel` or installing any `TransportIdentityBinding`, `AdmissionPrincipal`, Geyser login/game/outcome/accounting pipeline. No always-true `IdentityKeyVerifier` and no `acceptForwardedIdentity` bypass.
- Share native session/pending/replay and actual teardown accounting with players; enforce a small diagnostic quota (the staged host gate caps four active and sixteen retained attempts; an executor can choose a smaller scheduling quota). Disabled/saturated checker capacity is unavailable/unknown, not a false reachability failure. Retain reservations until native teardown really completes.
- Replay identity is context + attempt, not merely a hash of ciphertext: reminting with another nonce must not allocate another peer for the same retained attempt. The issuer fixes one attempt expiry; keep bounded replay metadata through that deadline. Controller reconnect does not clear it.
- Actual numeric destination/family/source allowlisting and hard256 UDP-send/1200-byte UDP-payload caps require native send-path support on both roles. Profile 1 pins one destination; profile 2 applies the provider-signed public same-family candidate and permits authenticated peer-reflexive selection. Separately bounded STUN-discovery monitor traffic is not included in peer send counters. A Java process timeout or a declared manifest is not enforcement. Default native MTU1280 can allow1232-byte UDP payloads, so1200 must not be claimed without explicit configuration and counted send-path tests. Do not cap the shared gameplay socket globally or break its independent STUN monitor.
- Report transport, local AUTH verification or submission, and optional PONG evidence separately. Preserve stale selected-path evidence without changing serving state. Distinguish target failure, checker failure, stale state and quota exhaustion.
- Run fresh first-contact checks before any host traffic to that checker IP. A successful probe contaminates that source; another source port is insufficient. Two regions corroborate reachability but cannot prove acceptance from arbitrary sources.
- Add actual native NXD1 rejection/acceptance, zero game/login promotion, dual-stack exact selected-pair, wrong DTLS/channel/offer/authority, replay, revocation and cleanup tests. The codec fixtures prove interoperability only; the separate opt-in host-gate tests exercise actual local native admission and teardown. Native/platform publication and real stock-client world-entry/gameplay gates remain separate.

The shared JSON contains public test secrets/private keys/nonces, explicitly marked never for deployment. Eight shared vectors cover both families, password22/24/30 and assisted profile 2. Fresh Java ES384 signatures are separately verified in Node/TypeScript, in addition to both implementations reproducing identical carrier/ICE-password/transcript/AUTH bytes.
