# Opt-in signed diagnostic host gate

This implementation is staged and is not advertised or enabled by discovery,
registration or Geyser configuration. A trusted integration explicitly calls
`NativeAdmissionServerChannel.enableDiagnostics(policy)` after binding. It
uses that exact existing UDP listener. Ordinary player traffic retains its
existing admission path; any `NXD1` prefix is quarantined even when malformed
or diagnostics are disabled. It cannot fall through to a player validator.

The installed policy supplies canonical provider/host/incarnation/generation,
at most eight diagnostic keys, at most sixteen exact numeric target/family/
candidate-revision tuples and a fixed authority deadline. Incoming permits
cannot add their own allowed target. Policy replacement revalidates diagnostic
attempts, including key material and expiry. Explicit trusted generation or
incarnation changes cancel old diagnostic attempts without touching player
children; changing endpoints never implicitly rotates either identity. Empty key
or endpoint sets withdraw diagnostics while retaining the listener, player
connections and replay history; a later valid policy can restore eligibility.

The gate owns four concurrent diagnostic slots and sixteen retained used
attempt IDs. Pending and active slots also count against the existing host's
shared session/pending limits. It retains used attempts through their original
expiry, including after successful cleanup or failed AUTH. A first packet with
bad STUN integrity allocates no native peer and does not burn a usable permit.
An already allocated attempt cannot reopen, move to another source tuple,
reset its send budget, or refresh its deadline. Issuers must independently
prevent reissuing a job with a new expiry; the host is not an unlimited journal.

The pending native peer pins the verified client DTLS fingerprint and accepts
only the numeric received source tuple, with the same address family as the
installed target. Reconstructed SDP contains those exact ICE credentials,
fingerprint and numeric tuple. It has no ICE server, TURN, TCP, arbitrary DNS,
identity assertion or untrusted original SDP. The full original offer digest
remains bound by the detached AUTH transcript; reconstruction does not claim
to reproduce that offer. The native agent receives an immutable 256-datagram,
1200-byte UDP-payload send limit and an absolute native monotonic deadline
before gathering or acceptance. MTU 1248 fits the declared payload cap.

There are exactly two channels: `ReliableDataChannel`, ordered/reliable; and
`UnreliableDataChannel`, unordered/unreliable with maxRetransmits 0. Both use an
empty protocol and zero packet-lifetime override. Host certificate/ufrag and
the presence of a native send budget are checked before acceptance. Pinned
DTLS and both channels must be established before one 217-byte NXDP AUTH is
verified for its secret-key binding and detached ES384 signature. The result
is only a `DiagnosticPrincipal`. No player child, CPK verifier, player identity
binding, game/login packet, or player pipeline event is created.

The prober sends AUTH on reliable, then waits for the first host challenge
before beginning its own exchange. This prevents its unordered challenge
overtaking AUTH. Native callbacks copy only bounded frames into per-attempt
queues; the host maintenance loop performs AUTH and exchange processing.

## Challenge and completion wire

Every post-AUTH frame is exactly 56 bytes:

| Offset | Bytes | Value |
|---|---:|---|
| 0 | 1 | NetherNet frame marker 0 |
| 1 | 4 | ASCII `NXDP` |
| 5 | 1 | Version 1 |
| 6 | 1 | Kind 2 challenge, 3 reply, 4 completion |
| 7 | 1 | Channel 0 reliable or 1 unreliable; completion requires 0 |
| 8 | 16 | Raw attempt ID |
| 24 | 32 | Random challenge, exact echoed challenge, or completion digest |

Each endpoint generates independent 32-byte random challenges for both channels.
A reply must match the original nonce and channel. A different nonce for an
already received challenge fails. Only the unreliable channel can repeat an
application challenge or reply, at most twice, preserving its nonce. Retry
spacing is at least 250 ms and cannot extend the attempt deadline.

Completion is SHA256 of UTF-8 `nxs-diagnostic-completion-v1` + a zero byte,
raw 16-byte attempt, then the four raw 32-byte nonces in this exact order:
prober reliable, prober unreliable, host reliable, host unreliable. Each side
sends one completion on reliable only after its own two challenge replies are
verified and both remote challenges have been replied to. Each side requires
the peer's valid completion before success. Reliable completion may overtake
an unreliable reply; at most one pending completion is retained and it cannot
cause early success. A reliable duplicate completion is invalid. Only the
bounded identical unreliable challenge/reply duplicates remain allowed.

The unchanged per-endpoint cap is 12 frames, 1024 sent and received application
bytes, 256 bytes per frame, including the prober's 217-byte AUTH. The no-loss
exchange sends five 56-byte host frames and AUTH plus five 56-byte prober frames.
Public independent bytes are in `diagnostic-exchange-v1.fixtures.json`.

## Results, time and cleanup

Maximum handshake is 15 seconds; the whole attempt retains its original
at-most 60-second permit deadline. Monotonic elapsed time prevents a backward
wall-clock adjustment from extending host policy or attempt validity. Expired
or withdrawn targets/keys cannot authenticate, send challenges or report
successful cleanup. Control reconnection and policy replacement do not renew
an existing diagnostic attempt.

The host captures actual selected numeric local/remote family and candidate
transport plus native UDP counters before destruction. The local selected port
and any specific bind address must match the existing gameplay listener. Each
result retains its original context, key ID, attempt, full offer digest, client
fingerprint, endpoint revision and expiry for stale-result correlation. It allows
250 ms bounded
completion flush time within the original deadline, then awaits actual native
cleanup before emitting a successful result. Missing selected-pair/statistics,
any observed native send rejection, post-completion protocol violation or deadline
expiry during required cleanup fails qualification. Cleanup failure retains
the native handle/count and makes the gate unavailable. A 32-entry report queue
is bounded and counts dropped reports; it does not publish readiness itself.

Reported counters are snapshots taken before native destruction, not final
post-destruction totals; the immutable send guard also applies to shutdown
traffic. The current native API does not retain counters after destruction.
An OS send counter is not delivery proof. The external verdict must combine
independent host and prober reports bound to the same job/attempt. This gate
does not supply workload identity, a public facade, job leases, target routing
allowlists, regional execution, stock-client compatibility or gameplay proof.
It requires unpublished native integration artifacts until their normal
upstream publication/release process is completed.
