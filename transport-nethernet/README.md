# netty-transport-nethernet

In NetworkM this module targets Java 21 and uses the
[EduGeyser webrtc-java fork](https://github.com/EduGeyser/webrtc-java), published
as `dev.kastle.webrtc:webrtc-java:1.0.4-edu.3`. The fork retains the
`dev.kastle.webrtc` Maven group and Java packages. Consumers need its custom
[WebRTC artifact repository](https://raw.githubusercontent.com/EduGeyser/webrtc-java/maven-repo/)
in addition to Maven Central, plus matching platform native libraries. The root
NetworkM build already configures that repository.

NetherNet's identity library uses SLF4J 2.0. Applications that use SLF4J logging
must provide a backend compatible with the 2.0 API. NetworkM leaves backend
selection to the application; its tests use the SLF4J bridge to Java logging.

## Usage

> [!IMPORTANT]
> This library requires the platform-specific WebRTC native libraries at runtime. See [EduGeyser/webrtc-java](https://github.com/EduGeyser/webrtc-java#usage) for instructions on how to include the native libraries in your project.

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

A known closed or unavailable transport, or a synchronous binding failure,
fails the Netty write and closes the channel through its normal write-error
path. Queued buffers are released and their pending writes fail as well.

A successful write means the binding call completed. It does not confirm
native engine acceptance or peer receipt: the current WebRTC binding logs
asynchronous send rejections without returning them to the write future.
Channel closures are reported through the existing state callbacks.

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
