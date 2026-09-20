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

Call `LibDataChannelArchDetect.initialize()` during startup. The native is loaded on first use, and the lookup path has to be set before anything touches libdatachannel.

For a separate artifact per platform, take the one matching classifier instead and keep the jar small:

```kotlin
dependencies {
    implementation("org.cloudburstmc.netty:netty-transport-nethernet:$netherNetVersion")
    runtimeOnly("dev.opencollab:libdatachannel-java:$libdatachannelVersion:windows-aarch64")
}
```

The classifiers are `windows-x86_64`, `windows-aarch64`, `macos-x86_64`, `macos-arm64`, plus `x86_64` and `aarch64` for Linux. Android ships from its own `libdatachannel-java-android` module.

> [!WARNING]
> Every classifier of one operating system carries its native under the same path, so putting several of them on one classpath resolves to whichever comes first. Use `arch-detect` instead of listing them.

### Examples

These projects use this library to provide Nethernet support. You can see their source code for examples of how to use this library:

- [Kas-tle/ProxyPass](https://github.com/Kas-tle/ProxyPass): Uses server and client to debug game packets over various connection types.
- [MCXboxBroadcast/Broadcaster](https://github.com/MCXboxBroadcast/Broadcaster): Uses server to allow Bedrock clients to transfer to other Bedrock servers via Xbox Live.
- [ViaVersion/ViaFabricPlus](https://github.com/ViaVersion/ViaFabricPlus): Uses client to connect to LAN games and Realms.
- [ViaVersion/ViaProxy](https://github.com/ViaVersion/ViaProxy): Uses client to connect to LAN games and Realms.
- [WaterdogPE/WaterdogPE](https://github.com/WaterdogPE/WaterdogPE): Uses server and client to proxy Bedrock players between servers.
- [GeyserMC/Geyser](https://github.com/GeyserMC/Geyser): Uses server to let Bedrock players join a Java server.

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