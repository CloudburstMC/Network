# Native diagnostic probe attempt

`NativeDiagnosticProbeAttempt` is a reusable production JVM transport owner for one
caller-authorized attempt. It uses libdatachannel with the existing Minecraft
transport profile and the signed diagnostic admission/answer codecs. It does not
provide a regional workload, public signaling endpoint, queue lease, issuer key,
job authentication or gameplay. The caller supplies the authorized original job,
fixed local address/port, trusted cached provider-diagnostic key catalog and a
synchronous current-authorization predicate. Constructing `Job` does not establish
any of that authority. An external coordinator must preserve attempt uniqueness,
original expiry, candidate revision and source history across retries/restarts.

The public constructor accepts exactly one numeric global-unicast target. It
reuses Network's special-purpose address classification, rejects private,
loopback, reserved/documentation and mapped-IPv6 target forms, and requires an
explicit resolved local bind of the same family. The package-private native-test
constructor permits loopback only; it does not admit private targets or DNS.
Resolved input addresses are copied through their numeric bytes. No hostname,
candidate list, ICE server, TURN relay, proxy, TCP transport, trickle or alternate
candidate retry is exposed by the API.

## One original attempt

`run(signaling)` executes once on a caller-owned bounded worker thread. The runner
creates no thread pool, timer queue or global retry loop. The caller must not run
it on a native callback, Netty event loop or an unbounded executor. Native event
callbacks only update bounded state/copy exact 56-byte diagnostic frames into a
12-entry queue; they do not perform cryptography, signaling or user callbacks.

The original absolute deadline is at most sixty seconds, aligned to the existing
whole-second permit format. Monotonic progress and observed forward wall-clock
corrections cannot be undone by later wall-clock rollback. The runner captures a
native monotonic deadline before identity/assertion/signaling work and uses
`createPeerWithUdpLimits` to install its immutable limit before any ICE gathering:
256 UDP datagrams, 1200 payload bytes each, and one exact numeric destination.
The selected local UDP port is fixed. Creation disables automatic negotiation and
TCP, configures no ICE servers, and retains MTU 1248/SCTP max-message-size 262144.
The native send guard also applies to shutdown traffic. The gathering-created ICE
agent must expose counters and show zero sends/rejections before signaling.

Gathering completes once. The complete original offer is validated, including one
UDP host candidate exactly equal to the configured local address/port, full ICE,
BUNDLE/mid 0 and the required SCTP profile. It is never rewritten to fit the
profile. Ephemeral random ICE credentials, native DTLS identity and a detached
ES384 assertion bind the original offer digest, client fingerprint, attempt,
context, target/family/revision, bounds profile and fixed expiry.

`Signaling.exchange(Request)` receives an owned bounded offer/assertion and returns
a `CompletionStage<String>` containing the existing provider-signed diagnostic
answer. This trusted callback must return promptly: the transport adapter owns
bounded HTTP response ingestion, request cancellation and any network operation.
It must not block before returning its stage or log/persist the sensitive request.
The runner never receives a reusable admission/permit secret and never accepts an
already-verified answer from the callback. It checks the answer's size before
parsing and independently verifies the signature, exact original request,
provider purpose, expected host fingerprint, single target candidate and expiry.
It consumes `VerifiedDiagnosticAnswer` once and rechecks current authority,
cancellation and deadline immediately before setting the remote description.
No peer traffic is allowed before this step. The signed answer carries the NXD1
permit that the host independently opens and authenticates.

The runner conservatively pins the entire original trusted catalog for this
attempt. A catalog change, even an unrelated key change, cancels it; there is no
in-place refresh or deadline renewal. Catalog readers and authorization predicates
must be bounded, synchronous, side-effect-free cached reads. No D1, KV or key URL
lookup is performed here. The fifteen-second handshake window includes gathering
and signaling. Only the existing bounded unreliable application challenges may
retry; they retain the original nonce and cannot extend the whole attempt.

## Exchange and result

Pinned DTLS and exactly two locally created channels must connect:
`ReliableDataChannel` is ordered/reliable; `UnreliableDataChannel` is
unordered/unreliable with maxRetransmits 0. Both use empty protocol and zero
packet-lifetime override. Extra remote channels, text, oversized or excess frames
fail. The runner sends one 217-byte AUTH on reliable, then requires a correctly
formed reliable host challenge before starting its own independent challenges.
It uses the shared `DiagnosticExchange` implementation for four verified round
trips and both completion digests, bounded by twelve frames, 1024 application
bytes and 256 bytes per frame. There is no Minecraft account, player identity,
game packet or game protocol.

A successful immutable result requires the complete exchange, actual selected UDP
pair with the exact configured local tuple and authorized remote tuple/family,
valid native send statistics with no rejection, and actual native cleanup. Up to
250 ms of completion flush time stays inside the original deadline. Clock expiry,
authority withdrawal or protocol violation during required completion/cleanup
prevents success. Cancellation and thread interruption initiate cleanup. One
runner can never create another peer or retry its job.

Cleanup waits at most five seconds after stopping work. If native teardown fails
or times out, the result reports `cleanupComplete=false`; the runner retains its
native handle and `termination()` reflects eventual destruction/failure. The
workload must retain its resource reservation or become unavailable until cleanup
settles, rather than treating a returned failed result as permission to replenish
capacity. `close()` requests cancellation and observes native termination immediately, even
if a violating signaling callback remains blocked before returning its stage.
That callback thread is separate unfinished work; its worker reservation cannot
be released until it returns. Cleanup may need to
continue after permit expiry, but the original native send deadline is unchanged.
The trusted signaling adapter must also release its own cancelled request.

Result fields retain original context/job/target, offer digest, client fingerprint,
observed transport/exchange stages, selected pair, frame counts and native deadline.
Application send counts include attempted channel handoffs; they are not a
separate acknowledgement or native OS-send counter.
A completion digest is exposed only on success. UDP counters are immutable
snapshots taken before native destruction, not final shutdown totals or proof of
delivery. A failed native transport can already have discarded its ICE agent;
then `udp` is null, never fabricated zero or a stale gather-time snapshot. Missing
statistics cannot qualify success. Successful local OS sends are not reachability
proof on their own.

The result is one endpoint's observation. A coordinator must correlate it with an
independent host report on the same context/attempt/target revision/offer and
completion digest. This runner does not certify a pristine first-contact source:
that requires separate observed public egress/source-history evidence and no prior
host traffic to that address. It cannot infer universal any-source NAT filtering,
IPv6 readiness from IPv4, stock Minecraft compatibility or gameplay from a pass.

## Local verification and dependency boundary

Tests use actual loopback IPv4/IPv6 UDP sockets, the production signed host gate,
random test-owned permit/provider keys, pinned DTLS, SCTP and the finite exchange.
The facade callback is local test code. Positives compare the independent host
completion digest and assert no player child/validation. Negatives cover wrong
provider signature, expected/actual DTLS pin, oversized answer, stale catalog,
authority withdrawal before/after peer traffic, cancellation/unavailable signaling,
wall/monotonic expiry, public-target rejection and one-use execution. No external
network traffic or regional deployment is part of this evidence.

The local pinned native chain is libjuice `19d507e`, libdatachannel `5f6c151` and
libdatachannel-java `70960d9`. These API additions are not yet normal published
artifacts. The tested JAR SHA256 is
`436df03c5d36fdcbc1b59567d2639fa8003156d8e0259229b3dbca961e8ae66a`, and JNI library
SHA256 is `63f3aaa931a551c6db360b249f0fd73eaf3ab0e9c11ae6ceb5ca8a9368f2a5c0`.
The normal release/dependency process and independent stock/libwebrtc release
interop remain separate gates.
