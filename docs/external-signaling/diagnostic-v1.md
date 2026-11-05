# Connectivity diagnostics

An opted-in host accepts a short-lived diagnostic on its gameplay socket,
exchanges one reliable PING/PONG, and closes. This checks transport
connectivity; it does not establish player authentication or gameplay.

Diagnostics reuse [NXS stateless admission](wire-reference.md#stateless-admission-carrier):
the same host key, incarnation audience, AES-GCM envelope, ICE password
derivation, STUN integrity check and pinned DTLS connection. They use the
reserved network ID `0`, which the host routes to the diagnostic handler before
creating a player reservation or channel.

## Authorization and signaling

The prober is the provider's own client that runs a check, for example from one
region. How it reaches the provider is up to the provider; this section states
what each side checks.

The provider first authorizes the attempt (the parent authorization): a fixed
attempt ID, host incarnation and generation, host DTLS fingerprint, target and
candidate revision, address family and expiry. It must authenticate the prober
and match each request to that authorization before issuing the diagnostic
admission token, the permit. A retry never extends its deadline.

The prober sends its exact gathered SDP offer through an authenticated provider
HTTPS connection. The provider validates the offer and returns the answer as
`application/sdp` in the SDP profile below. HTTPS authenticates the signaling
response; the authorized host fingerprint pins the subsequent DTLS connection.
The signaling adapter must reject redirects and bound the response body to
16,384 bytes.

Before installing the answer, the prober checks the host fingerprint, ICE
credential shape, transport profile and numeric destination. A direct answer
must match the originally authorized target. An assisted answer supplies one
public destination in the authorized family. Selected endpoints are checked
again before PING and before reporting success.

## Shared admission format

The permit is an ordinary [`NXS1` token](wire-reference.md#carry-the-token-in-the-ice-username)
for the host's current admission key and incarnation. The provider validates
instance, origin and generation against its current authorization; the host
captures its current policy and invalidates attempts when that policy is
withdrawn or replaced.

The plaintext keeps the ordinary 67-byte prefix and client password, with these
differences:

- `networkId` is unsigned 64-bit zero.
- The 16-byte player identity-binding slot carries the diagnostic attempt ID.
- A mandatory 59-byte diagnostic extension follows the client password.

| Extension offset | Bytes | Value |
| --- | --- | --- |
| 0 | 32 | SHA-256 of the exact gathered offer |
| 32 | 8 | Candidate revision, unsigned big endian |
| 40 | 1 | Profile 1 or 2; high bit selects IPv6 |
| 41 | 16 | Target address; IPv4 has twelve leading zero bytes |
| 57 | 2 | Target port, unsigned big endian |

Profile 1 names the direct target. Profile 2 uses an all-zero address and port
zero; the authenticated assisted exchange supplies the destination. IPv4-mapped
IPv6 encodings are rejected. Zero without this extension, and nonzero player
IDs with this extension, are invalid.

Diagnostic passwords contain 22 to 30 ICE characters, giving a token length of
`8 + ceil((154 + passwordBytes) * 4 / 3)`, at most 254 characters. The permit
expires within 60 seconds and before its original parent, host key and endpoint
policy deadlines. The host enforces a 15-second handshake limit, wall and
monotonic expiry, key withdrawal, and bounded replay history.

## Direct and assisted transport

Direct admission uses the NXS
[first-packet validation](wire-reference.md#validate-the-first-packet), which
checks STUN MESSAGE-INTEGRITY before allocating the peer. The host reconstructs
the pinned remote SDP and routes only network ID zero to the echo handler.

Assisted diagnostics use the ordinary
[`assisted-join` WebSocket frame](control-v1.md#assisted-joins) with
`networkId: "0"` and no `cpk`. The frame is accepted only through the current,
authenticated provider connection. Its permit, exact offer hash, credentials,
context, attempt ID and expiry must agree before peer creation. Unknown inbound
profile-2 permits cannot create a peer; a prepared peer may be reused.

Both paths use the host's gameplay listener, certificate and port. Assistance
may gather a fresh same-socket STUN mapping. An IPv4 prober behind NAT may offer
its private host address; the host waits for an authenticated incoming check to
learn the public peer-reflexive endpoint. Private addresses are not destinations
for host-initiated diagnostic traffic. Only public endpoints are allowed in
production.

The bounded SDP profile is one bundled `application` section, mid `0`,
`UDP/DTLS/SCTP webrtc-datachannel`, SCTP port 5000, maximum message size 262144,
one numeric UDP candidate and one end-of-candidates marker. Offers use
`actpass`; answers use `active`. ICE-lite, player identity assertions, duplicate
required attributes, extra candidates and answer remote-candidates are rejected.

## PING/PONG and cleanup

Both NetherNet data channels must open: `ReliableDataChannel` (ordered and
reliable) and `UnreliableDataChannel` (unordered, no retransmissions). Only the
reliable channel carries the diagnostic frame. Each endpoint accepts one 56-byte
binary frame, which is the whole data channel message without NetherNet segment
framing; extra, text, oversized or unreliable-channel messages fail the attempt.

| Offset | Bytes | Value |
| --- | --- | --- |
| 0 | 6 | `00 4e 58 44 50 01` |
| 6 | 1 | 2 PING or 3 PONG |
| 7 | 1 | Zero |
| 8 | 16 | Authorized attempt ID |
| 24 | 32 | Random PING nonce, echoed unchanged in PONG |

The prober reports success only after checking the echoed attempt/nonce,
selected endpoints and native cleanup. It closes immediately. The host records
PONG sent, allows at most one second for the client to close, then releases the
peer. Host observations do not assert that the prober received the response.

Diagnostics never enter the game pipeline or produce player admission events.
The host bounds active attempts, retained attempt IDs and queued results while
respecting shared listener capacity.

[Admission and SDP vectors](fixtures/diagnostic-v1.fixtures.json) and
[PING/PONG vectors](fixtures/diagnostic-exchange-v1.fixtures.json) define
interoperability. The [schema](diagnostic-v1.schema.json) describes the decoded
claims and fixture inputs, not JSON wire messages.
Run `node docs/external-signaling/tests/diagnostic-v1.fixtures.mjs` for independent
Node verification of the shared NXS envelope.
