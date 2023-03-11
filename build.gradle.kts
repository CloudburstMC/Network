/*
 * Copyright 2023 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    group = "org.cloudburstmc.netty"
    version = rootProject.property("version") as String

    repositories {
        mavenLocal()
        mavenCentral()
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
        withJavadocJar()
        withSourcesJar()
    }

    configure<PublishingExtension> {
        repositories {
            maven {
                name = "maven-deploy"
                url = uri(
                        System.getenv("MAVEN_DEPLOY_URL")
                                ?: "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                )
                credentials {
                    username = System.getenv("MAVEN_DEPLOY_USERNAME") ?: "username"
                    password = System.getenv("MAVEN_DEPLOY_PASSWORD") ?: "password"
                }
            }
        }
        publications {
            create<MavenPublication>("maven") {
                artifactId = "netty-${project.name}"

                from(components["java"])

                pom {
                    description.set(project.description)
                    url.set("https://github.com/CloudburstMC/Network")
                    inceptionYear.set("2018")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            name.set("CloudburstMC Team")
                            organization.set("CloudburstMC")
                            organizationUrl.set("https://github.com/CloudburstMC")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/CloudburstMC/Network.git")
                        developerConnection.set("scm:git:ssh://github.com:CloudburstMC/my-library.git")
                        url.set("https://github.com/CloudburstMC/Network")
                    }
                    ciManagement {
                        system.set("GitHub Actions")
                        url.set("https://github.com/CloudburstMC/Network/actions")
                    }
                    issueManagement {
                        system.set("GitHub Issues")
                        url.set("https://github.com/CloudburstMC/Network/issues")
                    }
                }
            }
        }
    }

    configure<SigningExtension> {
        if (System.getenv("PGP_SECRET") != null && System.getenv("PGP_PASSPHRASE") != null) {
            useInMemoryPgpKeys(System.getenv("PGP_SECRET"), System.getenv("PGP_PASSPHRASE"))
            sign(project.extensions.getByType(PublishingExtension::class).publications["maven"])
        }
    }

    tasks {
        named<JavaCompile>("compileJava") {
            options.encoding = "UTF-8"
        }
        named<Test>("test") {
            useJUnitPlatform()
        }
    }
}
