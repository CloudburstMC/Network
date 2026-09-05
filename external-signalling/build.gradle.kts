description = "NetherNet External Signalling client and stateless admission"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
dependencies {
    api(libs.gson)
    implementation(project(":transport-nethernet"))
    testImplementation(libs.bundles.junit)
    testImplementation(project(":transport-raknet"))
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly("${rootProject.property("nativeJavaGroup")}:libdatachannel-java:${rootProject.property("nativeJavaVersion")}:x86_64")
}
tasks.jar { manifest.attributes["Automatic-Module-Name"] = "org.cloudburstmc.netty.signalling" }


tasks.test { useJUnitPlatform { excludeTags("native") } }
tasks.register("nativeBenchClasspath") {
    dependsOn(tasks.testClasses)
    // Printing a classpath does not otherwise make Gradle build its project JARs.
    dependsOn(sourceSets.test.get().runtimeClasspath)
    doLast { println(sourceSets.test.get().runtimeClasspath.asPath) }
}
tasks.register<Test>("nativeAdmissionTest") {
    description = "Real fixed-UDP stateless host integration against the pinned JNI library"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    useJUnitPlatform { includeTags("native") }
    maxParallelForks = 1
    testLogging { showStandardStreams = true }
}

tasks.register<JavaExec>("providerStub") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.cloudburstmc.netty.signalling.IndependentProviderStub")
}

tasks.register<JavaExec>("providerBench") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.cloudburstmc.netty.signalling.ProviderBench")
    listOf("providerOrigin", "providerState", "providerMode", "providerToken", "providerRegistrationMode", "providerRegion", "providerPool", "providerTags", "providerHoldSeconds", "providerStopFile", "providerExtensionsFile").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
}

// Avoid shell-specific dependency-cache paths in the cross-repository local workflow.
tasks.register("providerBenchClasspath") {
    dependsOn(tasks.testClasses)
    dependsOn(sourceSets.test.get().runtimeClasspath)
    doLast { println(sourceSets.test.get().runtimeClasspath.asPath) }
}

tasks.processResources { from(rootProject.file("docs/external-signalling/nxs-v1.schema.json")) }

tasks.processTestResources {
    from(rootProject.file("docs/external-signalling/nxs-v1.fixtures.json"))
    from(rootProject.file("docs/external-signalling")) {
        include("stateless-admission-v1.fixtures.json", "cloudburst-protocol-vectors.v1.json", "provenance.json")
        into("nxs")
    }
}
