# Network

### Introduction

Network components used within Cloudburst projects.

### Components

- [`netty-transport-raknet`](transport-raknet/README.md) - A RakNet implementation based on Netty patterns
- [`netty-transport-nethernet`](transport-nethernet/README.md) - A NetherNet (WebRTC) implementation based on Netty patterns

### Compatibility

| Module | Java |
| --- | --- |
| `netty-transport-raknet` | 8 |
| `netty-transport-nethernet` | 21 |

Both modules use Netty 4.2, aligned through its BOM. Applications that provide
Netty themselves must use 4.2 modules. RakNet depends on `netty-codec-base`
rather than the aggregate `netty-codec` artifact, so it does not pull in
unrelated codecs.

### NetherNet natives

The NetherNet transport needs the WebRTC native library of the platform it runs
on. The Java API of [webrtc-java](https://github.com/EduGeyser/webrtc-java)
comes with the transport; add the native library as a runtime dependency with
the classifier of your platform, at the version the transport declares in
[gradle/libs.versions.toml](gradle/libs.versions.toml). This example targets
Linux x86-64:

```kotlin
dependencies {
    implementation("org.cloudburstmc.netty:netty-transport-nethernet:$networkVersion")
    runtimeOnly("io.github.sendablemetatype.webrtc:webrtc-java:$webrtcVersion:linux-x86_64")
}
```

Replace the classifier with the one for your platform, or include each platform
your application supports:

| Platform | Native classifier |
| --- | --- |
| Linux x86-64 | `linux-x86_64` |
| Linux ARM64 | `linux-aarch64` |
| Linux ARM32 | `linux-aarch32` |
| Windows x86-64 | `windows-x86_64` |
| Windows ARM64 | `windows-aarch64` |
| macOS x86-64 | `macos-x86_64` |
| macOS ARM64 | `macos-aarch64` |

See the [NetherNet README](transport-nethernet/README.md) for signaling and
channel configuration.

### Building

Run `./gradlew build`. The NetherNet module compiles and tests on Java 21, the
other modules on Java 8; Gradle provisions the toolchains it needs.

### Maven

##### Repository:

For releases, use Maven Central.
Snapshots can be found in the repository below.

<details open>
<summary>Gradle (Kotlin DSL)</summary>

```kotlin
repositories {
    maven("https://repo.opencollab.dev/maven-snapshots/")
}
```

</details>
<details>
<summary>Gradle</summary>

```groovy
repositories {
    maven {
        url 'https://repo.opencollab.dev/maven-snapshots/'
    }
}
```

</details>
<details>
<summary>Maven</summary>

```xml

<repositories>
  <repository>
    <id>opencollab-snapshots</id>
    <url>https://repo.opencollab.dev/maven-snapshots/</url>
  </repository>
</repositories>
```

</details>

