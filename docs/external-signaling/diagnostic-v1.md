# Connectivity diagnostics

An opted-in host accepts a short-lived diagnostic on its existing gameplay
socket, exchanges one reliable PING/PONG, and closes. This checks transport
connectivity; it does not establish player authentication or gameplay.

Diagnostics reuse [NXS stateless admission](wire-reference.md): the same host
key, incarnation audience, AES-GCM envelope, ICE password derivation, STUN
integrity check and pinned DTLS connection. Authenticated network ID `0` is
reserved for diagnostics. Player issuers reject it, and the host routes it to
the diagnostic handler before creating a player reservation or channel.

## Authorization and signaling

The caller authorizes a fixed attempt ID, host incarnation/generation, host
DTLS fingerprint, target/revision, address family and expiry. Provider-specific
workload credentials and scheduling stay outside Network. The provider must
authenticate the workload and match the request to that original authorization
before issuing admission. A retry never extends its deadline.

The prober sends its exact gathered SDP offer through an authenticated provider
HTTPS connection. The provider validates the offer and returns `application/sdp`
using the existing answer builder. There is no ephemeral assertion key, detached
offer proof, answer signing key catalog, signed-answer wrapper or application
AUTH message. HTTPS authenticates the signaling response; the authorized host
fingerprint pins the subsequent DTLS connection. The signaling adapter must
reject redirects and bound the response body to 16,384 bytes.

Before installing the answer, the prober checks the host fingerprint, ICE
credential shape, transport profile and numeric destination. A direct answer
must match the originally authorized target. An assisted answer supplies one
public destination in the authorized family. Selected native endpoints are
checked again before PING and before reporting success.

## Shared admission format

The token is `NXS1` + four-character key ID + unpadded standard Base64 of
`nonce12 || ciphertext || GCM-tag16`. Its existing audience is
`nxs-stateless-host-v1/<incarnation>`. The host-specific admission key and random
incarnation bind the recipient. The provider validates host ID, origin and
generation against its current authorization; the host captures its current
policy and invalidates attempts when that policy is withdrawn or replaced.

The ordinary 67-byte plaintext prefix and client password are unchanged:

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
IDs with this extension, are invalid. Existing player token bytes are unchanged.

Diagnostic passwords contain 22–30 ICE characters, giving a token length of
`8 + ceil((154 + passwordBytes) * 4 / 3)`, at most 254 characters. The permit
expires within 60 seconds and before its original parent, host key and endpoint
policy deadlines. The host enforces a 15-second handshake limit, wall and
monotonic expiry, key withdrawal, and bounded replay history.

## Direct and assisted transport

Direct admission runs through the existing NXS validator. Native code checks
STUN MESSAGE-INTEGRITY before allocating the peer. The host reconstructs the
pinned remote SDP and routes only network ID zero to the echo handler.

Assisted diagnostics use the ordinary `assisted-join` WebSocket frame with
`networkId: "0"` and no `cpk`. The frame is accepted only through the current,
authenticated provider connection. Its permit, exact offer hash, credentials,
context, attempt ID and expiry must agree before peer creation. Unknown inbound
profile-2 permits cannot create a peer; a prepared peer may be reused.

Both paths use the existing listener, certificate and gameplay port. Assistance
may gather a fresh same-socket STUN mapping. An IPv4 prober behind NAT may offer
its private host address; the host waits for an authenticated incoming check to
learn the public peer-reflexive endpoint. Private addresses are not destinations
for host-initiated diagnostic traffic. Public endpoints only are allowed in
production; an explicit test seam permits matching loopback families.

The bounded SDP profile is one bundled `application` section, mid `0`,
`UDP/DTLS/SCTP webrtc-datachannel`, SCTP port 5000, maximum message size 262144,
one numeric UDP candidate and one end-of-candidates marker. Offers use
`actpass`; answers use `active`. ICE-lite, player identity assertions, duplicate
required attributes, extra candidates and answer remote-candidates are rejected.

## PING/PONG and cleanup

Both NetherNet data channels must open with their standard labels and
reliability settings. Only the reliable channel carries the diagnostic frame.
Each endpoint accepts one 56-byte binary frame; extra, text, oversized or
unreliable-channel messages fail the attempt.

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
The gate bounds active attempts to four, retained attempt IDs to sixteen and
queued results to thirty-two, while respecting shared listener capacity.

[Schema](diagnostic-v1.schema.json), [admission and SDP vectors](diagnostic-v1.fixtures.json)
and [PING/PONG vectors](diagnostic-exchange-v1.fixtures.json) define interoperability.
Run `node docs/external-signaling/diagnostic-v1.fixtures.mjs` for independent
Node verification of the shared NXS envelope.
