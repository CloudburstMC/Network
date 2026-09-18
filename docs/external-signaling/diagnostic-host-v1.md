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
DTLS bind the original offer, attempt, target and expiry.
For profile 1, only the received numeric source tuple is reconstructed into remote SDP. Profile 2 requires a verified proactive WebSocket offer and assertion, scoped to a locally enabled assisted family; incoming stateless admission cannot enable it. No incoming DNS, STUN/TURN/TCP configuration is installed.

Diagnostics use ordinary ICE peers. Profile 1 validates one exact selected destination. Profile 2 starts with the authenticated peer candidate from the signed offer and permits same-family public peer-reflexive endpoints learned through authenticated ICE checks. The probe applies one signed fresh host candidate so ICE runs in both directions. A shared per-attempt gatherer serves player and diagnostic assistance; its STUN monitor stops at the original deadline or completion, without restarting background warming. Attempts last at
most 60 seconds, including a 15-second handshake. Four concurrent diagnostics also
count against shared player/diagnostic native capacity. Sixteen used attempt IDs
remain until their original expiry. Bad initial STUN integrity creates no peer and
does not consume a valid permit. Policy refresh never renews an existing attempt.
Key/context/endpoint withdrawal cancels it. Empty keys/endpoints retain replay history.

Exactly two channels use the Minecraft transport profile: `ReliableDataChannel`
ordered/reliable, and `UnreliableDataChannel` unordered with maxRetransmits 0. Both
have empty protocol and zero lifetime override. The host checks the connection, channel properties, selected endpoint and local
fingerprint/ICE identity before processing PING. The remote DTLS fingerprint is
pinned by the admitted offer. No player child, login or gameplay pipeline is created.

## Single ping/pong

The host sends no application message until it receives PING on the admitted
reliable channel. The prober sends one random nonce; the host echoes it once.

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

The prober checks the exact attempt ID and random nonce, then immediately closes.
Each side sends one 56-byte application frame. There is no AUTH, application retry,
completion handshake or receipt. Invalid kinds, wrong attempts, duplicates and
frames on the unreliable channel are rejected. Public bytes are in
`diagnostic-exchange-v1.fixtures.json`.

## Evidence and cleanup

Only the prober can report a verified round trip. The host records that it submitted
a pong; it cannot claim delivery. The host closes on client disconnect and retains
a one-second fallback deadline after sending PONG. The original attempt and
handshake deadlines cover clients that never send a valid PING.

The selected UDP family and endpoint must match the authorized attempt. Expiry,
withdrawal or protocol failure prevents qualification. Cleanup always closes the
native peer; failure retains the capacity reservation and makes the gate unavailable.
Local results are bounded to 32 observations with a dropped-result count.

The signed admission and signed answer contracts remain in `diagnostic-v1.md` and
`diagnostic-answer-v1.md`. A regional workload still needs trusted target permission,
rate limits and pristine-source history to claim a first-contact check. IPv4 and IPv6
are separate observations. Localhost tests do not establish deployment or stock-client gameplay.
