# netty-transport-nethernet

In NetworkM this module targets Java 21 and uses
`dev.kastle.webrtc:webrtc-java:1.0.4-edu.3`. Consumers need the custom
[WebRTC artifact repository](https://raw.githubusercontent.com/SendableMetatype/webrtc-java/maven-repo/)
in addition to Maven Central, plus matching platform native libraries. The root
NetworkM build already configures that repository. The release links below refer
to the upstream library, not a published NetworkM release.

NetherNet's identity library uses SLF4J 2.0. Applications that use SLF4J logging
must provide a backend compatible with the 2.0 API. NetworkM leaves backend
selection to the application; its tests use the SLF4J bridge to Java logging.

## Downloads

### Releases ![Maven Central Version](https://img.shields.io/maven-central/v/org.cloudburstmc.netty/netty-transport-nethernet?label=Maven%20Central&color=%233fb950)

The library is published to Maven Central. See the [latest release](https://github.com/Kas-tle/NetworkCompatible/releases/latest) for the latest version.

### Snapshots [![](https://jitpack.io/v/dev.kastle/NetworkCompatible.svg)](https://jitpack.io/#dev.kastle/NetworkCompatible)

Snapshots are available from [jitpack](https://jitpack.io/#dev.kastle/NetworkCompatible). Note the package group for jitpack is `dev.kastle.NetworkCompatible` witht the name `netty-transport-nethernet`.

## Usage

> [!IMPORTANT]
> This library requires the platform-specific WebRTC native libraries at runtime. See [Kas-tle/webrtc-java](https://github.com/Kas-tle/webrtc-java?tab=readme-ov-file#usage) for instructions on how to include the native libraries in your project.

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

### Examples

These projects use this library to provide Nethernet support. You can see their source code for examples of how to use this library:

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
