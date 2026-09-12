import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.application") version "9.4.0" apply false
    id("com.android.library") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.20" apply false
}

// The build must not depend on whichever JDK happens to be installed on the machine.
// JDK 26 breaks Robolectric's bytecode instrumentation ("Unsupported class file major
// version 70"), so the toolchain is pinned to a supported LTS and provisioned by Gradle
// (org.gradle.toolchains.foojay-resolver-convention in settings.gradle.kts).
val buildJdk = JavaLanguageVersion.of(21)

subprojects {
    group = "com.printbridge"
    // Published artifact version; also used for -Pprintbridge.versionName overrides.
    version = (findProperty("printbridge.versionName") as String?) ?: "0.1.0"

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
            toolchain.languageVersion.set(buildJdk)
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(21)
        }
        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // The Kotlin Android plugin is applied by AGP; configure it late so the pinned
    // JVM target also covers the Android modules (app, printer-bluetooth, printer-usb).
    plugins.withId("org.jetbrains.kotlin.android") {
        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // Maven publication for the reusable library modules (those that apply the
    // maven-publish plugin). Android AARs are intentionally not published: the app is the
    // only consumer and an AAR without versioned API guarantees would be misleading.
    // Credentials and target repository are documented in docs/RELEASE_PROCESS.md.
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications {
                register<MavenPublication>("library") {
                    from(components["java"])
                    pom {
                        name.set(project.name)
                        description.set("PrintBridge ${project.name} module")
                        url.set("https://github.com/Sensat10on/PrintBridge")
                        licenses {
                            license {
                                name.set("Proprietary")
                            }
                        }
                        scm {
                            connection.set("scm:git:https://github.com/Sensat10on/PrintBridge.git")
                            url.set("https://github.com/Sensat10on/PrintBridge")
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri(
                        (findProperty("printbridge.github.repository") as String?)
                            ?: "https://maven.pkg.github.com/Sensat10on/PrintBridge"
                    )
                    credentials {
                        username = (findProperty("printbridge.github.actor") as String?)
                            ?: System.getenv("GITHUB_ACTOR")
                            ?: System.getenv("PRINTBRIDGE_MAVEN_USER")
                        password = (findProperty("printbridge.github.token") as String?)
                            ?: System.getenv("GITHUB_TOKEN")
                            ?: System.getenv("PRINTBRIDGE_MAVEN_TOKEN")
                    }
                }
            }
        }

        // Consumable source jar, so a published artifact can be browsed in an IDE.
        val projectSourceSets = extensions.getByType<org.gradle.api.plugins.JavaPluginExtension>().sourceSets
        val sourcesJar = tasks.register<Jar>("sourcesJar") {
            archiveClassifier.set("sources")
            from(projectSourceSets.getByName("main").allSource)
        }
        extensions.configure<PublishingExtension> {
            publications.named("library", MavenPublication::class.java) {
                artifact(sourcesJar)
            }
        }
    }
}