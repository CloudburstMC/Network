# Native diagnostic probe attempt

`NativeDiagnosticProbeAttempt` performs one caller-authorized ICE/DTLS/SCTP check
using libdatachannel and the Minecraft transport profile. It does not enroll hosts,
authenticate workloads, lease queue jobs, retry another candidate or run gameplay.
`Job` is trusted local input, not proof of authority. Its original attempt, target,
revision, expected host fingerprint and expiry are immutable. Every successful attempt exchanges the single reliable PING/PONG described in
`diagnostic-host-v1.md`.

Profile 1 permits one numeric global-unicast destination and an exact same-family local bind/port. Profile 2 uses the zero target sentinel and an explicitly supplied numeric, same-family provider-advertised STUN endpoint when the probe needs public mapping discovery. Private, reserved, mapped-IPv6 and DNS targets fail
closed. Only the package-private native test constructor permits loopback. No TURN, proxy, TCP, trickle or unsigned answer candidate is exposed. The profile-2 monitor uses the same bound socket and closes with the attempt.

`run(signaling)` runs once on a caller-owned bounded worker using an ordinary ICE
peer. The attempt is at most 60 seconds; gathering, signing,
signaling and channel establishment must fit the original 15-second handshake.
Forward wall corrections and monotonic elapsed time prevent expiry extension by
clock rollback. Native callbacks retain only bounded frame copies. No executor,
retry scheduler or background journal is created by the runner.

After complete gathering, the runner validates its exact original offer and signs
an ephemeral P384 assertion. The tightly scoped `Signaling.exchange(Request)`
callback must promptly return a bounded asynchronous signed-answer body. The runner
independently checks provider purpose/signature, exact request, target, host DTLS
pin, catalog, cancellation and original expiry before installing remote SDP. It
never receives the reusable host admission key. Catalog and authorization readers
must be bounded synchronous local reads. They are rechecked across asynchronous
work and before native effects; a catalog change cancels this attempt.

Both correctly configured channels must open before the prober sends its only
PING. It verifies the matching PONG and immediately closes the connection. There
is no AUTH frame, application retry or completion handshake. A missing, malformed,
wrong-attempt or wrong-nonce PONG cannot produce success. No Minecraft packets or
accounts are involved.

Every success also requires the authorized selected UDP local/remote endpoints and family. Profile 1 pins the original tuples; profile 2 also permits a same-family public local peer-reflexive mapping learned through ICE when the mapping towards the host differs from STUN discovery. Success also requires
completed native cleanup. Results are
immutable local observations; there is no completion digest, host receipt or
required dual-party journal. First-contact reachability still requires independent
source-history evidence; one pass cannot establish universal any-source filtering.

Cancellation starts native close immediately, including while a misbehaving signaling
callback blocks. Cleanup waits up to five seconds. `cleanupComplete=false` requires
the caller to retain its capacity reservation until `termination()` settles; a blocked
callback similarly still occupies its own worker. No cleanup/retry renews the original
attempt deadline. Sensitive offers, ICE credentials, assertions and answers must
not be logged or persisted.

Focused tests use actual localhost IPv4/IPv6 UDP, pinned DTLS, SCTP, signed admission
and one ping/pong; no public traffic or game protocol. They cover forged/withdrawn
credentials, wrong pins, replay/quotas, missing or late signaling, original deadlines,
cancellation and exact cleanup. Run `nativeAdmissionTest` against the pinned JNI artifacts; dependency publication and independent client interop remain separate evidence.
