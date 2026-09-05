plugins {
    id("com.gradleup.nmcp")
}

description = "NetherNet transport for Netty"

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

    constraints {
        testImplementation(libs.jspecify)
    }
}

tasks.jar {
    manifest.attributes["Automatic-Module-Name"] = "org.cloudburstmc.netty.transport.nethernet"
}

tasks.register<JavaExec>("runDiscovery") {
    mainClass.set("org.cloudburstmc.netty.util.nethernet.NetherNetScanner")
    classpath = sourceSets["main"].runtimeClasspath
}
