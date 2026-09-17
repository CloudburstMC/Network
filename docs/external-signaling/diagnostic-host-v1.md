# Signed diagnostic transport

Diagnostics use the existing gameplay UDP mux, with separate signed admission.
The game server locally calls `NativeProviderTransport.configureDiagnostics(policy)`
or the lower-level `NativeAdmissionServerChannel.enableDiagnostics(policy)`.
`DiagnosticHostPolicy` owns the provider/host/incarnation/generation, at most eight
trusted diagnostic keys, at most 32 exact numeric family/address/port/revision
endpoints or explicitly assisted families, and their deadlines. Incoming permits cannot configure these values.
The transport bridge checks its own incarnation and advertised endpoints before
configuration; a withdrawn endpoint is removed from the same gate without clearing
its replay history. `disableDiagnostics()` cancels diagnostic eligibility. Neither
operation enables or disables player admission. Player drain is game-owned and
independent of diagnostics; closing the listener closes both.

There is no installation acknowledgement, owner-epoch protocol, completion upload
or completion receipt. Local observations do not alter serving state or health.
`NXD1` is quarantined even when malformed or diagnostics are disabled, so it cannot
fall through to the player validator. Signed permit, exact ICE credentials, pinned
DTLS and the detached ES384 AUTH bind the original offer, attempt, target and expiry.
For profile 1, only the received numeric source tuple is reconstructed into remote SDP. Profile 2 requires a verified proactive WebSocket offer and assertion, scoped to a locally enabled assisted family; incoming stateless admission cannot enable it. No incoming DNS, STUN/TURN/TCP configuration is installed.

The native send guard is installed before acceptance: 256 UDP datagrams, 1200-byte
payload cap and a fixed monotonic deadline. Profile 1 pins one exact destination. Profile 2 starts with the authenticated peer candidate from the signed offer and permits same-family public peer-reflexive endpoints learned through authenticated ICE checks. The probe applies one signed fresh host candidate so ICE runs in both directions. A shared per-attempt gatherer serves player and diagnostic assistance; its STUN monitor stops at the original deadline or completion, without restarting background warming. Attempts last at
most 60 seconds, including a 15-second handshake. Four concurrent diagnostics also
count against shared player/diagnostic native capacity. Sixteen used attempt IDs
remain until their original expiry. Bad initial STUN integrity creates no peer and
does not consume a valid permit. Policy refresh never renews an existing attempt.
Key/context/endpoint withdrawal cancels it. Empty keys/endpoints retain replay history.

Exactly two channels use the Minecraft transport profile: `ReliableDataChannel`
ordered/reliable, and `UnreliableDataChannel` unordered with maxRetransmits 0. Both
have empty protocol and zero lifetime override. The host checks connection, channel
properties, local fingerprint/ICE identity and native limits before accepting the
217-byte signed AUTH on reliable. It creates only `DiagnosticPrincipal`; there is no
player child, player identity verifier, login, gameplay or player pipeline event.

## Optional ping/pong

The host sends no application message until it receives authenticated PING. The
prober may finish after submitting AUTH, or request one random ping on reliable.
Reliable AUTH and PING are ordered; the unreliable channel needs no application exchange.

Every PING/PONG is exactly 56 bytes:

| Offset | Bytes | Value |
|---|---:|---|
| 0 | 1 | NetherNet marker 0 |
| 1 | 4 | ASCII `NXDP` |
| 5 | 1 | Version 1 |
| 6 | 1 | 2 PING or 3 PONG |
| 7 | 1 | 0 reliable |
| 8 | 16 | Original attempt ID |
| 24 | 32 | Random PING nonce or exact echoed nonce |

The prober generates one random nonce and checks its exact echo. No application
retry, second-channel ping, completion handshake or transcript digest is required.
Kind 4 and PING/PONG on unreliable are invalid. Including AUTH, the prober sends
two frames (273 bytes) and the host sends one (56 bytes). The hard receive bounds
remain 12 frames, 1024 bytes and 256 bytes per frame. Public PING/PONG bytes are in
`diagnostic-exchange-v1.fixtures.json`.

## Evidence and cleanup

The host reports its own AUTH verification; it cannot claim its PONG was delivered.
After authentication it allows one second for an optional PING within the original
deadline, then closes. The prober's `pingVerified` requires its original PONG. Without ping, its result
claims transport establishment and AUTH submission only, not remote application
verification. Neither result is gameplay proof or universal NAT classification.

Success requires the authorized UDP family and endpoint (including authenticated peer-reflexive selection for profile 2), available bounded native send
statistics with no rejection, and actual native cleanup. Native counters are
pre-destruction snapshots, not final totals or delivery receipts. Clock expiry,
withdrawal or protocol failure prevents qualification. Failed cleanup retains the
native reservation and makes the host gate unavailable. Local results are bounded
to 32 queued observations with a dropped-result count; no durable journal is required.

The signed admission and signed answer contracts remain in `diagnostic-v1.md` and
`diagnostic-answer-v1.md`. A regional workload still needs trusted target permission,
rate limits and pristine-source history to claim a first-contact check. IPv4 and IPv6
are separate observations. The native budget API requires the pinned JNI chain; localhost tests do not establish deployment or stock-client gameplay.
