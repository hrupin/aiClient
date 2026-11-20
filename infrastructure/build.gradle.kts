import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.publish.maven.MavenPublication

plugins {
    `maven-publish`
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

afterEvaluate {
    // Rename the publications after Kotlin has assigned the platform artifact IDs.
    publishing.publications.withType<MavenPublication>().forEach {
        it.artifactId = if (it.name == "kotlinMultiplatform") "kmp" else "kmp-${it.name.lowercase()}"
    }
}

kotlin {
    jvm {
        val testCompilation = compilations.named("test")
        val openAiTestPackage = "in.hrup.aiclient.infrastructure.providers.openai"
        val liveTextTestPackage = "in.hrup.aiclient.infrastructure.providers"
        val integrationTests = mapOf(
            "openAiIntegrationTest" to "$openAiTestPackage.OpenAiLiveIntegrationTest",
            "openAiVisionIntegrationTest" to "$openAiTestPackage.OpenAiVisionIntegrationTest",
            "providerVisionIntegrationTest" to "$openAiTestPackage.ProviderVisionIntegrationTest",
            "bflIntegrationTest" to "$liveTextTestPackage.bfl.BflLiveIntegrationTest",
            "mcpToolsIntegrationTest" to "in.hrup.aiclient.infrastructure.mcp.McpToolsIntegrationTest",
            "openAiRemoteMcpIntegrationTest" to "$openAiTestPackage.OpenAiRemoteMcpIntegrationTest",
            "deepSeekIntegrationTest" to "$liveTextTestPackage.DeepSeekLiveIntegrationTest",
            "xaiIntegrationTest" to "$liveTextTestPackage.XaiLiveIntegrationTest",
            "zaiIntegrationTest" to "$liveTextTestPackage.ZaiLiveIntegrationTest",
            "kimiIntegrationTest" to "$liveTextTestPackage.KimiLiveIntegrationTest",
            "perplexityIntegrationTest" to "$liveTextTestPackage.PerplexityLiveIntegrationTest",
            "providerMatrixIntegrationTest" to "$liveTextTestPackage.ProviderMatrixIntegrationTest",
        )

        testRuns.named("test") {
            executionTask.configure {
                integrationTests.values.forEach { filter.excludeTestsMatching(it) }
            }
        }

        integrationTests.forEach { (taskName, testClass) ->
            tasks.register<Test>(taskName) {
                group = "verification"
                description = "Runs the live integration test $testClass."
                val compilation = testCompilation.get()
                testClassesDirs = compilation.output.classesDirs
                classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles
                filter.includeTestsMatching(testClass)
                testLogging.showStandardStreams = true
                if (taskName == "providerMatrixIntegrationTest") {
                    reports.junitXml.isOutputPerTestCase = true
                    providers.gradleProperty("aiMcpUrl").orNull?.let { systemProperty("aiMcpUrl", it) }
                    providers.gradleProperty("aiClientMatrixResults").orNull?.let {
                        val output = file(it)
                        reports.junitXml.outputLocation.set(output)
                        reports.html.outputLocation.set(output.resolve("html"))
                        binaryResultsDirectory.set(output.resolve("binary"))
                    }
                }
                workingDir(rootProject.layout.projectDirectory.asFile)
                providers.gradleProperty("aiProvider").orNull?.let { systemProperty("aiProvider", it) }
                providers.gradleProperty("aiModel").orNull?.let { systemProperty("aiModel", it) }
                doNotTrackState("The test exercises a live service.")
            }
        }
    }
    iosArm64()
    iosSimulatorArm64()
    android {
        namespace = "in.hrup.aiclient.infrastructure"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest {}
        withSourcesJar()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.coroutines.core)
            api(libs.ktor.client.core)
            implementation(libs.serialization.json)
            implementation(libs.json.schema.validator)
        }
        jvmMain.dependencies { implementation(libs.ktor.client.cio) }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies { implementation(libs.ktor.client.mock) }
    }
}
