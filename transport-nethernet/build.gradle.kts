description = "NetherNet transport for Netty"

dependencies {
    api(libs.bundles.netty)
    api(libs.netty.codec.http)
    api(libs.netty.codec.haproxy)
    api(libs.expiringmap)
    api(libs.libdatachannel.java)

    implementation(libs.gson)
    implementation(libs.jose4j)
    // Reading and writing the host identity PEM, which the JDK offers no API for
    implementation(libs.bouncycastle.pkix)

    // Annotations only, CLASS retention, so consumers need nothing at runtime
    compileOnly(libs.jspecify)

    testImplementation(libs.bundles.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
    // The selected pair test talks to a real peer, so it needs the native library
    testRuntimeOnly(variantOf(libs.libdatachannel.java) { classifier("x86_64") })
}

configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withJavadocJar()
    withSourcesJar()
}

tasks.jar {
    manifest.attributes["Automatic-Module-Name"] = "org.cloudburstmc.netty.transport.nethernet"
}

tasks.register<JavaExec>("runDiscovery") {
    mainClass.set("org.cloudburstmc.netty.util.nethernet.NetherNetScanner")
    classpath = sourceSets["main"].runtimeClasspath
}