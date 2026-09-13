description = "NetherNet transport for Netty"

// The transport needs Java 21 while the rest of the build stays on its baseline.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("-release", "21")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    minHeapSize = "512m"
    maxHeapSize = "1024m"
}

val webrtcNativePlatform = providers.gradleProperty("webrtcNativePlatform")

dependencies {
    api(platform(libs.netty.bom))
    api(libs.bundles.netty)
    api(libs.netty.codec.http)
    api(libs.webrtc.java)

    implementation(libs.gson)
    implementation(libs.jose4j)
    // Direct declarations preserve the transitive upgrades for Maven consumers too.
    implementation(libs.slf4j.api)
    implementation(libs.errorprone.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.jdk14)

    if (webrtcNativePlatform.isPresent) {
        val webrtc = libs.webrtc.java.get()
        testRuntimeOnly("${webrtc.module.group}:${webrtc.module.name}:${webrtc.versionConstraint.requiredVersion}:${webrtcNativePlatform.get()}")
    }

    constraints {
        testImplementation(libs.jspecify)
    }
}

tasks.test {
    systemProperty("webrtc.nativeTests", webrtcNativePlatform.isPresent.toString())
    if (webrtcNativePlatform.isPresent) {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

tasks.jar {
    manifest.attributes["Automatic-Module-Name"] = "org.cloudburstmc.netty.transport.nethernet"
}

tasks.register<JavaExec>("runDiscovery") {
    mainClass.set("org.cloudburstmc.netty.util.nethernet.NetherNetScanner")
    classpath = sourceSets["main"].runtimeClasspath
}
