import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}

subprojects {
    group = "in.hrup.aiclient"
    version = providers.gradleProperty("aiClientVersion").get()

    val githubRepository = providers.environmentVariable("GITHUB_REPOSITORY")
        .orElse(providers.gradleProperty("githubRepository"))
    val githubActor = providers.environmentVariable("GITHUB_ACTOR")
    val githubToken = providers.environmentVariable("GITHUB_TOKEN")

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            val repositoryPath = githubRepository.orNull
            if (repositoryPath != null) {
                publications.withType<MavenPublication>().configureEach {
                    pom.url.set("https://github.com/$repositoryPath")
                }
                repositories.maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/${repositoryPath.lowercase()}")
                    credentials {
                        username = githubActor.orNull
                        password = githubToken.orNull
                    }
                }
            }
        }
    }
}
