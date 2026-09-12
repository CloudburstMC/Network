# netty-transport-nethernet

In NetworkM this module targets Java 21 and uses the
[slim webrtc-java fork](https://github.com/EduGeyser/webrtc-java),
with Maven coordinates `io.github.sendablemetatype.webrtc:webrtc-java` and Java
packages under `io.github.sendablemetatype.webrtc`. Consumers also need matching
platform native libraries.

NetherNet's identity library uses SLF4J 2.0. Applications that use SLF4J logging
must provide a backend compatible with the 2.0 API. NetworkM leaves backend
selection to the application; its tests use the SLF4J bridge to Java logging.

## Usage

> [!IMPORTANT]
> This library requires the platform-specific WebRTC native libraries at runtime. See the [WebRTC usage guide](https://github.com/EduGeyser/webrtc-java#usage) for instructions on how to include the native libraries in your project.

### Network IDs

HTTP and Xbox signaling treat peer NetworkIDs as opaque strings. HTTP callers
must encode the ID as one UTF-8 URL path segment in `/v1/join/{networkId}`.
The listener decodes percent escapes once and preserves literal `+` characters,
leading zeros and numeric-looking IDs outside the uint64 range. Missing IDs,
malformed percent escapes, invalid UTF-8 and unescaped path delimiters receive
HTTP 400.

The Xbox WebSocket path encodes the local ID as a single segment; JSON signaling
keeps peer IDs as strings. Generated IDs remain decimal strings for compatibility.
LAN discovery still uses uint64 IDs in its binary wire format. The numeric
`NetherNetAddress` constructor and `getNetworkIdAsLong()` remain available for
that representation; use `getNetworkId()` when handling opaque IDs.

### HTTP offer authentication

`NetherNetHttpSignaling` requires a valid client identity assertion by default.
Before creating a WebRTC peer, it verifies the Minecraft auth service's token
signature, issuer, audience, expiry and subject, then checks the detached ES384
signature binding the token's P-384 client key to every SDP fingerprint. Missing
or invalid assertions receive HTTP 400. Self-signed tokens are rejected by this
default policy.

Trusted signing keys come from Minecraft's authorization service and are cached;
URLs in the client's assertion cannot select a different trust source. Key
fetching and verification run on a bounded executor outside the I/O loops. The
existing negotiation deadline covers validation too, and a full validation queue
receives HTTP 503. These checks run only during connection setup.

To add application authorization, wrap the default verifier and reject claims
that your application does not allow:

```java
ClientAssertionValidator verifier = new ClientAssertionValidator();
signaling.setOfferValidator(sdp -> {
    ClientIdentity identity = verifier.validate(sdp);
    if (!allowedXuids.contains(identity.getClaims().get("xid"))) {
        throw new GeneralSecurityException("Player is not allowed");
    }
    return identity;
});
```

Validators may run concurrently. A custom validator must return a verified
`ClientIdentity` or throw an exception to reject the offer. Configure the trusted
issuer-key constructor of `ClientAssertionValidator` for a private issuer.

For an endpoint that deliberately accepts unvalidated offers, opt out explicitly:

```java
signaling.setOfferValidator(null);
```

Accepted child channels expose the verified identity through
`child.getClientIdentity()` or
`channel.attr(NetherNetChildChannel.CLIENT_IDENTITY).get()`. It is null when offer
validation is disabled or unsupported by the signaling path. Applications still
own Bedrock Login authentication and must check that the Login identity matches
the verified transport key; NetworkM does not parse Login packets. Set discovery
authentication flags to match the endpoint's admission policy.

This policy applies to HTTP offers. LAN and Xbox signaling behavior is unchanged,
as is the server identity assertion attached to answers.

### LAN advertisements

`NetherNetDiscovery` emits binary ServerData v6, matching stable Bedrock
1.26.45.1. The scanner and `NetherNetServerDataCodec` decode v6 and reject
unsupported versions or malformed records. Preview's v7 format is deferred
until a stable release adopts it.

`PongData` includes `acceptsOnlineAuth`, `acceptsSelfSignedAuth`, and `nonce`.
The existing nine-argument constructor and builder methods remain available.
Both authentication flags default to true; consumers with a different admission
policy must set them explicitly. These fields describe policy and do not enable
authentication or Login nonce checks.

The default nonce is generated once for the process and reused across new
builders and advertisement updates. Set it explicitly when sharing a nonce with
another endpoint, such as HTTP server status:

```java
PongData data = new PongData.Builder()
        .setServerName("My server")
        .setLevelName("My world")
        .setAcceptsOnlineAuth(true)
        .setAcceptsSelfSignedAuth(false)
        .setNonce(sharedNonce)
        .build();
signaling.setAdvertisementData(data);
```

Advertisements are encoded when updated and cached for discovery responses.
An update that cannot fit a UDP datagram fails without replacing the previous
advertisement. This library support does not enable a LAN listener in EduGeyser.

### Reading and buffering

Server and client channels receive messages from both data channels. Unreliable
frames use `NetherNetUnreliableFrame` until `NetherNetFramingCodec` validates
their zero header and emits the payload as a `ByteBuf`. Invalid unreliable
frames are dropped without changing reliable reassembly. All outbound writes
continue to use reliable delivery.

`AUTO_READ=false` pauses inbound delivery. A raw channel `read()` consumes one
transport frame. With `NetherNetFramingCodec` installed, that read
continues until one complete application message from either channel is ready.
An unreliable message can be delivered while a reliable message is incomplete;
the reliable fragments are retained for reassembly. There is no ordering
guarantee between the two data channels.
The next message waits for another read. Enabling auto-read drains queued frames
in batches of at most 64, yielding between batches.

The inbound queue is limited to 512 frames and 32 MiB plus 512 header bytes,
enough for two messages at the codec's existing 16 MiB/256-fragment limits.
Overflow closes the channel and releases queued buffers. The native API cannot
pause reception, so disabling reads cannot allow an unlimited backlog. These
limits apply to both data channels together, before and after activation.

### Outgoing message sizes

The remote SDP's SCTP media sections determine the outgoing fragment size,
including the one-byte NetherNet header. An absent `a=max-message-size` uses
65,536 bytes, as specified by RFC 8841. Zero means unlimited. A positive limit
is respected, with a local ceiling of 262,144 bytes. This ceiling also applies
to caller overrides and fixed-size framing codecs; it is local policy, not a
NetherNet protocol limit. Malformed attributes and a one-byte limit fail negotiation.

Attributes outside active SCTP application sections do not set this limit.
If several applicable limits occur, the smallest is used. The existing
256-fragment limit remains in place. Peers advertising 262,144 bytes keep the
same framing as before.

### Write failures

A write future succeeds when the native send operation accepts every frame
of the message. It does not acknowledge peer receipt. Multiple frames may be
in flight; completion handling is coalesced on the channel's event loop.
The Java payload buffer is released as soon as the native binding has copied
it; waiting for acceptance retains only the completion state.

An explicit flush submits queued frames as long as the engine has capacity,
without waiting for earlier send results. If a bounded event loop rejects
completion processing, a 10 ms retry prevents a quiet connection from getting
stuck. Explicit flushes bypass that retry; it adds no batching window.
Rejected inbound-delivery tasks retain their bounded queue and share this
recovery wakeup. New incoming frames and manual reads can retry delivery
immediately, without waiting for the fallback.

A native rejection or a failure preparing a send closes the connection and
fails pending writes, releasing their buffers. Retrying a missing fragment
after later frames were submitted could corrupt the reliable stream. Buffer
drain notifications control backpressure separately from send acceptance.

Custom `WebRtcSession` backends implement `send(data, completion)` and report
null for transport acceptance or a failure cause. The callback must return
promptly and may run on an engine thread. Custom channels use a
`NetherNetUnsafe` subclass so send failures use the same close path as the
built-in client and server channels.

### Native integration tests

The default test suite uses controlled backends. To also exercise native send
acceptance and rejection on the client and server paths, select the native
classifier for the test JVM, for example:

```shell
./gradlew :transport-nethernet:test -PwebrtcNativePlatform=linux-x86_64
```

CI selects the native classifier for each runner and includes these tests in
the build and release checks.

### Upstream integration examples

These references were inherited from NetworkCompatible. They illustrate
upstream API usage; adapt their imports and dependencies for NetworkM.

- [Kas-tle/ProxyPass](https://github.com/Kas-tle/ProxyPass): Uses server and client to debug game packets over various connection types.
- [MCXboxBroadcast/Broadcaster](https://github.com/MCXboxBroadcast/Broadcaster): Uses server to allow Bedrock clients to transfer to other Bedrock servers via Xbox Live.
- [ViaVersion/ViaFabricPlus](https://github.com/ViaVersion/ViaFabricPlus): Uses client to connect to LAN games and Realms.
- [ViaVersion/ViaProxy](https://github.com/ViaVersion/ViaProxy): Uses client to connect to LAN games and Realms.

## Packet Flow

### Client

---

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="../.github/readme/nethernet_client_dark.svg">
  <img src="../.github/readme/nethernet_client_light.svg">
</picture>

### Server

---

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="../.github/readme/nethernet_server_dark.svg">
  <img src="../.github/readme/nethernet_server_light.svg">
</picture>
