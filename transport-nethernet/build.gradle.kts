plugins {
    id("com.gradleup.nmcp")
}

description = "NetherNet transport for Netty"

dependencies {
    api(libs.bundles.netty)
    api(libs.netty.codec.http)
    api(libs.expiringmap)
    api(libs.webrtc.java)

    implementation(libs.gson)
    implementation(libs.jose4j)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    manifest.attributes["Automatic-Module-Name"] = "org.cloudburstmc.netty.transport.nethernet"
}

tasks.register<JavaExec>("runDiscovery") {
    mainClass.set("org.cloudburstmc.netty.util.nethernet.NetherNetScanner")
    classpath = sourceSets["main"].runtimeClasspath
}
