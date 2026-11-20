# aiClient

Kotlin Multiplatform library for AI providers.

- `:core` defines the domain models and contracts.
- `:application` implements use cases and depends on `:core`.
- `:infrastructure` implements provider and protocol adapters and depends on `:core`.

The modules target JVM (including Desktop), Android, iOS ARM64 devices, and iOS ARM64 simulators.

## Install from GitHub Packages

Add the library repository to your application's `settings.gradle.kts`. Replace
`owner/repository` with the lowercase GitHub repository path. For local
consumption, set `GITHUB_ACTOR` to your GitHub username and `GITHUB_TOKEN` to a
classic token with `read:packages` and access to the package.

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.github.com/owner/repository") {
            content { includeGroup("in.hrup.aiclient") }
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}
```

In a KMP module, add the dependency to `commonMain.dependencies`. In a JVM or
Android application, use the same dependency in the module's `dependencies` block.

```kotlin
implementation("in.hrup.aiclient:kmp:0.1.0")
```

`kmp` brings in `core` transitively. If you only need the domain API,
use `in.hrup.aiclient:core:0.1.0`.

```kotlin
import `in`.hrup.aiclient.infrastructure.catalog.providerModelCatalog

val modelIds = providerModelCatalog["deepseek"].orEmpty().map { it.id }
```

## Publish a release

Set the release version in `gradle.properties`. In the GitHub repository that
contains this project, run **Actions → Publish aiClient → Run workflow**.
It publishes `core` and `kmp` with Gradle metadata and their JVM,
Android and iOS variants. Use a new version for each publication.

To validate locally, run `./gradlew publishToMavenLocal` and use `mavenLocal()`
in the consumer's repository configuration.

## Development

Check the build on an Apple Silicon Mac with JDK 21, Android SDK 37, and Xcode:

```sh
./gradlew projects
./gradlew compileCommonMainKotlinMetadata compileKotlinJvm compileAndroidMain compileKotlinIosArm64 compileKotlinIosSimulatorArm64
./gradlew jvmTest testAndroidHostTest iosSimulatorArm64Test
```

### Live OpenAI integration test

Set `OPENAI_API_KEY` in the ignored `.env` file at the project root or in the environment.
The test defaults to provider `openai` and model `gpt-6-luna`.

OpenAI model IDs for this test include `gpt-6-luna`, `gpt-6.1-terra`,
`gpt-6.2-sol`, and `gpt-6.2-astra`. See the [OpenAI model catalog](https://developers.openai.com/api/docs/models).

```sh
./gradlew :infrastructure:openAiIntegrationTest
./gradlew :infrastructure:openAiIntegrationTest -PaiProvider=openai -PaiModel=gpt-6.2-sol
```

Command-line properties take precedence over `AI_PROVIDER` and `AI_MODEL`
in the environment, which take precedence over values in `.env`.
OpenAI is currently the available provider. The selected model must support Structured Outputs.

This task sends one request to the OpenAI API and checks the validated result.
The regular `jvmTest` task uses local test doubles and needs no API credentials.

### Live MCP tools test

```sh
./gradlew :infrastructure:mcpToolsIntegrationTest
```

The test queries `https://agpt.hrup.in/mcp` with `tools/list`, validates the list,
and prints each tool's metadata and input schema. It runs only when requested.

### Live OpenAI Remote MCP test

```sh
./gradlew :infrastructure:openAiRemoteMcpIntegrationTest
```

This test uses the same OpenAI settings as `openAiIntegrationTest`. It allows
only `get_available_models` on the MCP server, checks the tool call in the
Responses output, and validates and prints the generated model list.
