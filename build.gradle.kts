import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension

plugins {
    // Share the plugin classloaders across the KMP modules.
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}

val centralStaging = layout.buildDirectory.dir("central-staging")
val apiDocumentation = layout.projectDirectory.file("docs/api-reference.md")
val licenseName = providers.environmentVariable("CENTRAL_LICENSE_NAME")
val licenseUrl = providers.environmentVariable("CENTRAL_LICENSE_URL")
val signingKey = providers.environmentVariable("CENTRAL_SIGNING_KEY")
val signingPassword = providers.environmentVariable("CENTRAL_SIGNING_PASSWORD")

subprojects {
    group = "in.hrup.aiclient"
    version = providers.gradleProperty("aiClientVersion").get()

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                val publication = name
                val documentationJar = tasks.register<Jar>(
                    "central${publication.replaceFirstChar(Char::uppercaseChar)}DocumentationJar",
                ) {
                    archiveClassifier.set("javadoc")
                    destinationDirectory.set(layout.buildDirectory.dir("central-docs/$publication"))
                    from(apiDocumentation)
                }
                artifact(documentationJar)
                pom {
                    name.set(if (project.name == "core") "aiClient Core" else "aiClient KMP")
                    description.set("Kotlin Multiplatform client for AI providers, structured output, and MCP tools")
                    url.set("https://github.com/hrupin/aiClient")
                    if (licenseName.isPresent && licenseUrl.isPresent) {
                        licenses {
                            license {
                                name.set(licenseName)
                                url.set(licenseUrl)
                            }
                        }
                    }
                    developers {
                        developer {
                            id.set("hrupin")
                            url.set("https://github.com/hrupin")
                        }
                    }
                    scm {
                        url.set("https://github.com/hrupin/aiClient")
                        connection.set("scm:git:https://github.com/hrupin/aiClient.git")
                        developerConnection.set("scm:git:ssh://git@github.com/hrupin/aiClient.git")
                    }
                }
            }
            repositories.maven {
                name = "CentralStaging"
                url = uri(centralStaging)
            }
        }

        signingKey.orNull?.let { key ->
            pluginManager.apply("signing")
            val publishing = extensions.getByType<PublishingExtension>()
            extensions.configure<SigningExtension> {
                useInMemoryPgpKeys(key, signingPassword.orElse("").get())
                sign(publishing.publications)
            }
        }
    }
}
