# netty-transport-nethernet

## Usage

> [!IMPORTANT]
> This library uses [libdatachannel-java](https://github.com/opencollab-incubator/libdatachannel-java) and needs its platform-specific native library at runtime. The main artifact contains no natives, so you have to add them yourself.

For a build that ships to more than one platform, add `libdatachannel-java-arch-detect`. It bundles every architecture and selects the matching one at runtime.

```kotlin
dependencies {
    implementation("org.cloudburstmc.netty:netty-transport-nethernet:$netherNetVersion")
    implementation("dev.opencollab:libdatachannel-java-arch-detect:$libdatachannelVersion")
}
```

The native is loaded on first use, and the bundle's per architecture layout is found without any setup code.

For a separate artifact per platform, take the one matching classifier instead and keep the jar small:

```kotlin
dependencies {
    implementation("org.cloudburstmc.netty:netty-transport-nethernet:$netherNetVersion")
    runtimeOnly("dev.opencollab:libdatachannel-java:$libdatachannelVersion:windows-aarch64")
}
```

The classifiers are `linux-x86_64`, `linux-aarch64`, `windows-x86_64`, `windows-aarch64`, `macos-x86_64` and `macos-arm64`. Android ships from its own `libdatachannel-java-android` module.

> [!WARNING]
> Every classifier of one operating system carries its native under the same path, so putting several of them on one classpath resolves to whichever comes first. Use `arch-detect` instead of listing them.

### Server

```java
OperatorIdentity identity = OperatorIdentity.fromPemOrCreate(new File("identity.pem"), "My Server");
NetherNetHTTPServerSignaling signaling = new NetherNetHTTPServerSignaling.Builder()
        .setIdentity(identity)
        .setMotd(new PongData.Builder().setServerName("My Server").setProtocol(protocol).setVersion(version).build())
        // Retail clients present an Xbox issued token; a proxy built with this library signs its own
        .setTokenTrust(TokenTrust.MINECRAFT_AUTH)
        .build();

new ServerBootstrap()
        .group(group) // any transport, the signaling listener runs on a loop of its own
        .channelFactory(NetherNetChannelFactory.server(signaling))
        // Media on its own UDP port when another transport holds the signaling port's
        .option(NetherChannelOption.NETHER_SERVER_ICE_ADDRESS, new InetSocketAddress(host, 19133))
        .childHandler(initializer)
        .bind(host, 19132);
```

The identity file is created on first start and pinned by clients, so keep it. `NetherNetChildChannel.PLAYER_INFO` on an accepted channel carries the validated player, and `TransportIdentityBinding` ties the login chain to it.

### Client

```java
OperatorIdentity player = identity.forPlayer(xuid, name); // per connection, the token expires
// A client holding a token the auth service issued for its key presents that instead:
// OperatorIdentity.fromToken(sessionKeyPair, multiplayerToken, "https://authorization.franchise.minecraft-services.net/")

new Bootstrap()
        .group(group)
        .channelFactory(NetherNetChannelFactory.client(NetherNetHTTPClientSignaling::new)) // one per connection
        .option(NetherChannelOption.NETHER_CLIENT_IDENTITY, player)
        .option(NetherChannelOption.NETHER_CLIENT_SERVER_TRUST, TokenTrust.pinnedTo(serverKey)) // optional
        .option(NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS, 10_000)
        .handler(initializer)
        .connect(serverAddress);
```

The server has to trust an operator signed identity, which is `TokenTrust.ANY`; the default refuses it with 401. The client does what the retail client does: it probes over HTTPS, falls back to plaintext when there is no TLS, and posts the offer over whichever answered. `HttpSignalingSettings` fixes the scheme instead, `HTTPS` for a hop that must stay private, and carries the trust for a private CA and the ICE servers. A server serving TLS refuses plaintext by default.

A client that knows the server can pin it: the connect then fails unless the answer is signed by the identity behind `serverKey`. The server side reads that key as `identity.publicKey()`, and `IdentityUtils.encodePublicKey` and `decodePublicKey` carry it through a config as text. Without the option the answer's identity is not checked.

### Examples

These projects use this library to provide Nethernet support. You can see their source code for examples of how to use this library:

- [Kas-tle/ProxyPass](https://github.com/Kas-tle/ProxyPass): Uses server and client to debug game packets over various connection types.
- [MCXboxBroadcast/Broadcaster](https://github.com/MCXboxBroadcast/Broadcaster): Uses server to allow Bedrock clients to transfer to other Bedrock servers via Xbox Live.
- [ViaVersion/ViaFabricPlus](https://github.com/ViaVersion/ViaFabricPlus): Uses client to connect to LAN games and Realms.
- [ViaVersion/ViaProxy](https://github.com/ViaVersion/ViaProxy): Uses client to connect to LAN games and Realms.
- [WaterdogPE/WaterdogPE](https://github.com/WaterdogPE/WaterdogPE): Uses server and client to proxy Bedrock players between servers.
- [GeyserMC/Geyser](https://github.com/GeyserMC/Geyser): Uses server to let Bedrock players join a Java server.

## Reading a client's error screen

The codeword the retail client shows is a theme, and the number after the stage in its details is
the actual reason. Both are decoded in [client-disconnect-reasons.md](../docs/nethernet/client-disconnect-reasons.md).

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