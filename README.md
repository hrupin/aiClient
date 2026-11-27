# aiClient

aiClient is a Kotlin Multiplatform library for text generation with OpenAI,
DeepSeek, xAI, Z.ai, Kimi, and Perplexity. It supports request–response
generation, streaming with Kotlin Flow, Structured Output validated against
JSON Schema, image analysis with vision-capable models, image generation
with Black Forest Labs, and tool calls through Local MCP.

Targets include JVM (server and Desktop), Android (API 24+), iOS arm64, and
iOS Simulator arm64.

See the [API reference](docs/api-reference.md) for request and response contracts,
MCP behavior, and error handling.

## Installation

With `mavenCentral()` in your project's repositories, add this dependency.
Use `commonMain.dependencies` in a KMP module or the module's
`dependencies` block in a JVM or Android application.

```kotlin
implementation("in.hrup.aiclient:kmp:0.1.12")
```

Use a version available in Maven Central. The `kmp` artifact includes
`core` as a transitive dependency. For domain models and contracts alone,
use `implementation("in.hrup.aiclient:core:0.1.12")`.

## Dependency injection

Register an `AiClient` in your application's dependency container. Pass in
the application configuration and a shared `HttpClient`. The application
loads secrets and closes the HTTP client at shutdown. You can inject your
own Ktor client or use `createHttpClient()`, which selects CIO on JVM,
OkHttp on Android, and Darwin on iOS.

```kotlin
import `in`.hrup.aiclient.AiClient
import `in`.hrup.aiclient.AiClientConfig
import `in`.hrup.aiclient.AiProviderConfig
import `in`.hrup.aiclient.AiProviderId
import `in`.hrup.aiclient.McpServerConfig
import `in`.hrup.aiclient.infrastructure.http.createHttpClient

val httpClient = createHttpClient()
val ai = AiClient(
    httpClient = httpClient,
    config = AiClientConfig(
        defaultProvider = AiProviderId.OPENAI,
        providers = mapOf(
            AiProviderId.OPENAI to AiProviderConfig(apiKey = openAiKey),
            AiProviderId.KIMI to AiProviderConfig(
                apiKey = kimiKey,
                defaultModel = "kimi-k3",
            ),
        ),
        mcpServers = mapOf(
            "catalog" to McpServerConfig(endpoint = "https://agpt.hrup.in/mcp"),
        ),
    ),
)
```

The application supplies `openAiKey` and `kimiKey`. Use
`AiProviderConfig.endpoint` to override the full provider URL. To authenticate
to an MCP server, pass connection headers to `McpServerConfig`, such as
`headers = mapOf("Authorization" to "Bearer $mcpToken")`.

OpenAI is the default provider. For prompt-based calls, model selection uses
the call's `model` argument first, then the configured `defaultModel`, then
the provider default. Overloads that accept an `AiRequest` use `request.model`.

| Provider | API key setting | Default model |
| --- | --- | --- |
| `OPENAI` | `OPENAI_API_KEY` | `gpt-6-luna` |
| `DEEPSEEK` | `DEEPSEEK_API_KEY` | `deepseek-flash` |
| `XAI` | `XAI_API_KEY` | `grok-4.7` |
| `ZAI` | `ZAI_API_KEY` | `glm-5.3` |
| `KIMI` | `KIMI_API_KEY` | `kimi-k3.1-pro` |
| `PERPLEXITY` | `PERPLEXITY_API_KEY` | `perplexity/sonar` |
| `BFL` | `BFL_API_KEY` | `flux-3-image` (image generation) |

The setting names appear in error messages and are used by live tests.
Applications supply the values through `AiProviderConfig.apiKey`. Configure
keys for the providers your application uses. A missing or blank key raises
`MissingAiApiKeyException` before any network request, when calling
`generate()` or collecting the flow from `stream()`.

### Model catalog

Read the bundled catalog without credentials or network access.

```kotlin
import `in`.hrup.aiclient.AiCatalog
import `in`.hrup.aiclient.AiProviderId
import `in`.hrup.aiclient.toJson

val modelsByProvider = AiCatalog.modelsByProvider
val deepSeekModels = modelsByProvider.getValue(AiProviderId.DEEPSEEK)
val defaultModel = AiCatalog.getDefaultModel(AiProviderId.DEEPSEEK)
val selected = AiCatalog.getModel("deepseek:deepseek-flash")

val catalogJson = modelsByProvider.toJson()
val deepSeekJson = deepSeekModels.toJson()
val selectedJson = selected.toJson()
```

`getDefaultModel(provider)` returns the provider's built-in default as an
`AiCatalogModel`. A client can override its default through
`AiProviderConfig.defaultModel`.

Each model includes a `key`, such as `deepseek:deepseek-flash`, with the
provider name in lowercase. All three `toJson()` formats include it on
each model object. Pass the stored key as-is to `getModel()`, which matches
it exactly. When no key matches, `getModel()` returns `null` and
`toJson()` encodes it as the JSON literal `null`.

Each entry provides a `provider` and a `modelId` to pass as the `provider`
and `model` arguments to `generate()` or `stream()`. For BFL, pass
`modelId` as the `model` argument to `generateImage()`. Model availability
depends on the provider API and your account. See the [catalog usage
example](docs/api-reference.md#model-catalog) in the API reference.

## Four generation modes

Run these examples from a suspend context using the configured `ai` above.

### 1. Request–response without a schema

```kotlin
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage

val response = ai.generate(
    prompt = "Explain Kotlin Flow in one sentence.",
    provider = AiProviderId.KIMI,
    model = "kimi-k3",
)
val text = response.output.filterIsInstance<AiMessage>()
    .flatMap { it.content }
    .filterIsInstance<AiContentPart.Text>()
    .joinToString("") { it.text }
println(text)
```

### 2. Streaming without a schema

```kotlin
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import kotlinx.coroutines.flow.collect

ai.stream("Give three short examples of using Kotlin Flow.").collect { event ->
    when (event) {
        is AiStreamEvent.TextDelta -> print(event.text)
        is AiStreamEvent.OutputItemCompleted -> Unit
        is AiStreamEvent.Completed -> println("\nTokens: ${event.response.usage?.totalTokens}")
    }
}
```

The flow is cold. Each collection starts a new request, and cancelling the
collecting coroutine cancels the request. A successful flow ends with
`Completed`. Failures propagate through the flow.

### 3. Request–response with JSON Schema

```kotlin
import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import `in`.hrup.aiclient.model.value.AiValue

val resultFormat = parseJsonResponseFormat(
    "extraction_result",
    """
    {
      "type": "object",
      "properties": {
        "title": {"type": "string"},
        "tags": {"type": "array", "items": {"type": "string"}},
        "details": {
          "type": "object",
          "properties": {
            "language": {"type": "string"},
            "confidence": {"type": "number"},
            "reviewed": {"type": "boolean"}
          },
          "required": ["language", "confidence", "reviewed"],
          "additionalProperties": false
        }
      },
      "required": ["title", "tags", "details"],
      "additionalProperties": false
    }
    """.trimIndent(),
)

val extractionPrompt = "Extract a title, tags, and metadata from: An introduction to Kotlin Flow."
val structured = requireNotNull(
    ai.generate(extractionPrompt, responseFormat = resultFormat).structuredOutput,
)
val title = (structured.fields.getValue("title") as AiValue.Text).value
println(title)
```

`structuredOutput` contains a validated `AiValue.Object`. Read `content` for
the validated JSON as text. The
response root must be an object. A failed validation raises
`StructuredOutputException`, whose `violations` field contains the schema
violations when available.

OpenAI, DeepSeek, xAI, Perplexity, and `kimi-k3` receive the schema through
their API's structured output format. Z.ai and the other Kimi models use JSON
mode with the schema in the generation instructions. aiClient validates the
completed result locally in every case. If `kimi-k3` wraps a single object in
an array, the adapter extracts the object and validates it against the original
schema.

### 4. Streaming with JSON Schema

```kotlin
ai.stream(extractionPrompt, responseFormat = resultFormat).collect { event ->
    when (event) {
        is AiStreamEvent.TextDelta -> print(event.text)
        is AiStreamEvent.OutputItemCompleted -> Unit
        is AiStreamEvent.Completed -> {
            val validated = requireNotNull(event.response.structuredOutput)
            println("\nResult fields: ${validated.fields.keys}")
        }
    }
}
```

Deltas may contain partial JSON. Use `Completed.response.structuredOutput`
when application logic depends on validated data. If `kimi-k3` starts a
response with an array wrapper, the adapter collects it before emitting the
normalized object.

## Image analysis

Use a vision-capable model and pass the prompt and image together in a user
message. The following models are covered by the live vision suite.

| Provider | Model | Image sources |
| --- | --- | --- |
| OpenAI | `gpt-6-luna` | Public URL, Base64 |
| DeepSeek | `deepseek-flash` | Public URL, Base64 |
| xAI | `grok-4.7` | Public URL, Base64 |
| Z.ai | `glm-5v-turbo` | Public URL, Base64 |
| Kimi | `kimi-k3.1-pro` | Public URL converted to Base64 by aiClient, or Base64 |
| Perplexity Agent API | `openai/gpt-5.6-luna` | Public HTTPS URL, Base64 |

For inline data, pass the image bytes as plain Base64 with their media type.
Kimi's endpoint accepts inline Base64. When you supply a URL for Kimi,
aiClient downloads the image using the application's HTTP client, validates
the response media type and a 32 MiB per-image limit, and sends a Base64 data
URL to Kimi. Pass trusted image URLs to this client-side download path.

```kotlin
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiImageSource
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest

val image = AiImageSource.Url("https://example.com/image.jpg")
// For local bytes, use AiImageSource.Base64(encodedImage, "image/jpeg").
val visionRequest = AiRequest(
    model = AiModel("gpt-6-luna"),
    input = listOf(AiMessage(AiRole.USER, listOf(
        AiContentPart.Text("Describe this image."),
        AiContentPart.Image(image),
    ))),
)
val description = ai.generate(visionRequest, provider = AiProviderId.OPENAI).content
```

For streaming, pass the same request to `ai.stream()`. To require a JSON
Schema in either mode, set `responseFormat` in the request. aiClient builds
the data URL for Base64 inputs. The captured request body includes the image
URL or Base64 data, so account for image contents when retaining logs.

Run the opt-in vision tests for each provider and image source across all
four modes.

```sh
./gradlew :infrastructure:openAiVisionIntegrationTest
./gradlew :infrastructure:providerVisionIntegrationTest
```

## Image generation with Black Forest Labs

Add `AiProviderId.BFL to AiProviderConfig(apiKey = bflKey)` to the provider map
in `AiClientConfig`. Call `generateImage()` from a suspend context.

```kotlin
import `in`.hrup.aiclient.model.image.AiImageFormat

val result = ai.generateImage(
    prompt = "A small blue ceramic cup on a plain white background.",
    model = "flux-3-image-klein",
    outputFormat = AiImageFormat.PNG,
    width = 1017,
    height = 775,
)
println(result.imageUrl)
println(result.costCredits)
println(result.request) // The JSON sent to BFL contains width=1024 and height=768.
```

Use `AiImageFormat.PNG` or `AiImageFormat.JPEG` with FLUX 3 Image Klein
and FLUX.2. FLUX 3 Image (`flux-3-image`) uses the provider's output format.
Omit `outputFormat` when using that model.

Set `width` and `height` in pixels for generation or editing. Supply both
together for FLUX 3 Image and FLUX 3 Image Klein. Omit both to let BFL
choose the dimensions. For these two models, the client raises sides below
the minimum, lowers sides above the maximum, and rounds to the nearest
multiple of 16. It also reduces the size as needed to meet the model's
pixel and aspect ratio limits. See the [size limits and examples](docs/api-reference.md#image-dimensions).

Pass `images` to edit or combine existing images. The list preserves input
order and supports public HTTP(S) URLs and raw Base64.

```kotlin
import `in`.hrup.aiclient.model.conversation.AiImageSource

val edited = ai.generateImage(
    prompt = "Change the background to a warm studio setting.",
    images = listOf(AiImageSource.Url("https://example.com/source.jpg")),
    model = "flux-2-pro",
    outputFormat = AiImageFormat.JPEG,
)
println(edited.imageUrl)
```

For local image bytes, use `AiImageSource.Base64(encodedImage, "image/jpeg")`.
Pass the raw Base64 string. FLUX 3 supports up to ten references. FLUX.2
supports up to eight references, or four for FLUX.2 Klein.

aiClient submits the job, polls the URL returned by BFL, and returns an
`AiImageResponse` when the job is ready. Use `imageUrl` to download the result,
`request` and `response` to inspect the submitted JSON and final poll, and
`costCredits` for the reported price in BFL credits. Delivery links expire
after ten minutes. The default model comes from the BFL provider configuration
or falls back to `flux-3-image`. See the [image API reference](docs/api-reference.md#image-generation)
for response fields and polling behavior.

For a custom BFL endpoint, set `AiProviderConfig.endpoint` to the API base
URL, such as `https://api.eu.bfl.ai/v1`. The library appends the model ID.

Set `BFL_API_KEY` in the environment or local `.env` to run the opt-in test.

```sh
./gradlew :infrastructure:bflIntegrationTest
# Image editing only
./gradlew :infrastructure:bflIntegrationTest --tests '*editsImageFrom*'
```

## Final results and provider logging

`generate()` returns an `AiResponse`. A successful `stream()` exposes the same
type in `AiStreamEvent.Completed.response`. Use `content` for the final assistant
text or validated JSON, and use `request` and `response` for diagnostics.

| Field | Value |
| --- | --- |
| `content` | Final assistant text or validated JSON text. |
| `request` | The JSON body actually sent to the AI provider. |
| `response` | The original provider response body, including received SSE frames through completion for streaming calls. |
| `provider`, `model` | Provider ID and the requested model ID. |
| `tokensSent`, `tokensReceived` | Provider-reported input and output tokens across all provider turns. Each count defaults to zero if absent. |
| `structuredOutput` | The validated `AiValue.Object` when a response schema was requested. |

The example uses a logging callback so the application controls where to store
provider payloads and how to redact them.

```kotlin
import `in`.hrup.aiclient.AiClient
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import kotlinx.coroutines.flow.collect

private fun logExchange(result: AiResponse, log: (String) -> Unit) {
    log("Provider ${result.provider}, model ${result.model}")
    log("Input tokens ${result.tokensSent}, output tokens ${result.tokensReceived}")
    log("Request body: ${result.request}")
    log("Response body: ${result.response}")
}

suspend fun generateAndLog(ai: AiClient, log: (String) -> Unit): String {
    val result = ai.generate("Explain Kotlin Flow in one sentence.")
    logExchange(result, log)
    return result.content
}

suspend fun streamAndLog(ai: AiClient, log: (String) -> Unit) {
    ai.stream("Explain Kotlin Flow in one sentence.").collect { event ->
        when (event) {
            is AiStreamEvent.TextDelta -> print(event.text)
            is AiStreamEvent.OutputItemCompleted -> Unit
            is AiStreamEvent.Completed -> logExchange(event.response, log)
        }
    }
}
```

These fields capture the final provider exchange. With Local MCP, the final
request body includes the tool result when a tool runs. HTTP headers and separate
intermediate model and MCP exchanges are outside these fields. Provider bodies may
contain user and tool data, so redact sensitive content before persisting logs.
See the [API reference](docs/api-reference.md#provider-exchange-capture) for
details about the capture behavior.

## Conversation roles and history

Use the `AiRequest` overload when you need explicit message roles or a multi-turn
conversation. Keep the messages in chronological order and store the history in
your application. For each new turn, add the user's message, send a snapshot of
the history, and append the assistant messages from the completed response.

| Role | Purpose |
| --- | --- |
| `SYSTEM` | Persistent instructions for the conversation. |
| `DEVELOPER` | Application instructions on adapters and models that support this role, such as OpenAI Responses. |
| `USER` | A message from the user. |
| `ASSISTANT` | A previous model response. |
| `TOOL` | Represented by `AiToolResult` in the domain model. Use the MCP API below to execute tools through `AiClient`. |

### Request–response with history

```kotlin
import `in`.hrup.aiclient.AiClient
import `in`.hrup.aiclient.AiProviderId
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest

fun message(role: AiRole, text: String): AiMessage =
    AiMessage(role, listOf(AiContentPart.Text(text)))

suspend fun chatWithHistory(ai: AiClient): MutableList<AiMessage> {
    val model = AiModel("gpt-6-luna")
    val history = mutableListOf(
        message(AiRole.SYSTEM, "You teach Kotlin to beginners."),
        message(AiRole.DEVELOPER, "Keep each answer under 100 words."),
    )

    history += message(AiRole.USER, "What is Kotlin Flow?")
    val first = ai.generate(
        request = AiRequest(model = model, input = history.toList()),
        provider = AiProviderId.OPENAI,
    )
    history += first.output.filterIsInstance<AiMessage>()
        .filter { it.role == AiRole.ASSISTANT }

    history += message(AiRole.USER, "Can you show a small example of that?")
    val second = ai.generate(
        request = AiRequest(model = model, input = history.toList()),
        provider = AiProviderId.OPENAI,
    )
    history += second.output.filterIsInstance<AiMessage>()
        .filter { it.role == AiRole.ASSISTANT }
    return history
}
```

Use `SYSTEM`, `USER`, and `ASSISTANT` for text conversations shared across
providers. Check role support before including `DEVELOPER` with a different
provider or model.

### Streaming with history

Pass the same history snapshot to `stream()`. Display text deltas as they arrive
and append the assistant's message once, from `Completed`.

```kotlin
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import kotlinx.coroutines.flow.collect

suspend fun streamNextTurn(ai: AiClient, history: MutableList<AiMessage>) {
    history += message(AiRole.USER, "How do I cancel collection?")
    val request = AiRequest(model = AiModel("gpt-6-luna"), input = history.toList())

    ai.stream(request, provider = AiProviderId.OPENAI).collect { event ->
        when (event) {
            is AiStreamEvent.TextDelta -> print(event.text)
            is AiStreamEvent.OutputItemCompleted -> Unit
            is AiStreamEvent.Completed -> {
                history += event.response.output.filterIsInstance<AiMessage>()
                    .filter { it.role == AiRole.ASSISTANT }
            }
        }
    }
}
```

Call `streamNextTurn(ai, chatWithHistory(ai))` to stream a third turn.

To use JSON Schema in either mode, set `responseFormat` on the `AiRequest`.
You can also pass `mcpTools` to either overload alongside the history.

## Local MCP and request-scoped tools

Register each MCP server by ID in the DI configuration with its URL and
connection headers. Select the tools separately for each request. In
`mcpTools`, each key is a configured server ID and each value is the set of
tool names the model can use for that request. An empty selection makes no
MCP requests.

```kotlin
val catalogTools = mapOf("catalog" to setOf("get_available_models"))
val prompt = "Use MCP to list models. Return the ID and provider of the first model."

val modelFormat = parseJsonResponseFormat(
    "model",
    """
    {
      "type": "object",
      "properties": {
        "id": {"type": "string"},
        "provider": {"type": "string"}
      },
      "required": ["id", "provider"],
      "additionalProperties": false
    }
    """.trimIndent(),
)

// Request–response with text or JSON Schema.
val answer = ai.generate(prompt, mcpTools = catalogTools)
val jsonAnswer = ai.generate(prompt, responseFormat = modelFormat, mcpTools = catalogTools)

// Streaming with text or JSON Schema. Collect the chosen flow to start the request.
val textEvents = ai.stream(prompt, mcpTools = catalogTools)
val jsonEvents = ai.stream(prompt, responseFormat = modelFormat, mcpTools = catalogTools)
```

aiClient discovers the selected tools with `tools/list`. In the default
`AUTO` strategy, it uses native Function Calling for supported models or
iterative JSON selection through Structured Output. The model receives the
results of each batch before it chooses the next one, so later calls can
use earlier results. Tools with the same name on different servers get
distinct aliases. The caller's schema still applies to the final answer.

Configure strategy, timeouts, and generation limits through
`AiClientConfig.mcpToolOptions`. Streaming delivers the final answer and one
`Completed` event with usage summed across the provider turns. See the
[API reference](docs/api-reference.md#local-mcp) for details.

When using an adapter directly, the `in.hrup.aiclient.infrastructure.mcp`
package provides `StreamableHttpMcpClient`, `generateWithMcpTools()`, and
`streamWithMcpTools()` for model-selected tools. Use `generateWithMcp()`
or `streamWithMcp()` with `McpToolInvocation` for explicit tool calls.
The public `AiClient` uses Local MCP. An opt-in integration test covers
OpenAI Remote MCP.

## Error handling

| Error | When it occurs |
| --- | --- |
| `MissingAiApiKeyException` | The selected provider has no configured API key. The `provider` property identifies the provider. |
| `StructuredOutputException` | Model output or tool arguments failed JSON Schema validation. |
| `McpException` | The MCP protocol or tool call failed, or the model selected an unavailable tool. |
| `OpenAiResponsesException`, `DeepSeekResponsesException`, `ProviderResponseException` | The provider returned an error. These exceptions carry an HTTP status and, when available, a request ID and provider error code. |
| `IllegalArgumentException` | An invalid call parameter was supplied, such as an unknown MCP server ID. |

Network failures can propagate as Ktor exceptions. The calling coroutine
controls cancellation.

## Tests

Regular JVM tests use local test doubles and run without API keys or network
requests.

```sh
./gradlew jvmTest
```

For live tests, supply the keys from the provider table through the environment
or the Git-ignored `.env` file in the repository root.

```sh
# All models in the catalog
./scripts/test-provider-matrix.sh

# All eight scenarios for one model
./scripts/test-provider-matrix.sh --tests '*[KIMI/kimi-k3]'

# Four MCP scenarios for one model using a real MCP server
./scripts/test-provider-matrix.sh -PaiMcpUrl=https://agpt.hrup.in/mcp \
  --tests '*mcp*[KIMI/kimi-k3]'
```

The script requires Python 3. It checks four modes without MCP and four with MCP
for every text model. By default, MCP calls use a simulated server and AI calls
use the live APIs. The `fixture-model` and `fixture-provider` values come
from the simulated server. Use `-PaiMcpUrl` to run the MCP scenarios against
a real server. BFL rows are marked `SKIP`. The summary shows provider, model,
mode, status, and response or error. The script exits with a nonzero status if
a check fails.

You can also run individual integration tasks.

| Provider or scenario | Gradle task |
| --- | --- |
| OpenAI | `:infrastructure:openAiIntegrationTest` |
| BFL image generation | `:infrastructure:bflIntegrationTest` |
| OpenAI image analysis | `:infrastructure:openAiVisionIntegrationTest` |
| Other providers' image analysis | `:infrastructure:providerVisionIntegrationTest` |
| DeepSeek | `:infrastructure:deepSeekIntegrationTest` |
| xAI | `:infrastructure:xaiIntegrationTest` |
| Z.ai | `:infrastructure:zaiIntegrationTest` |
| Kimi | `:infrastructure:kimiIntegrationTest` |
| Perplexity | `:infrastructure:perplexityIntegrationTest` |
| MCP tool discovery | `:infrastructure:mcpToolsIntegrationTest` |
| OpenAI Remote MCP with Structured Output | `:infrastructure:openAiRemoteMcpIntegrationTest` |

Use `-PaiModel` to select a model in an individual provider integration test.

```sh
./gradlew :infrastructure:kimiIntegrationTest -PaiModel=kimi-k3
```

## Development and publishing

- `:core` contains domain models and contracts. It has no Ktor or
  kotlinx.serialization dependencies.
- `:application` contains the tool execution loop and its policies. It depends
  on `:core` and is published as a transitive dependency of `kmp`.
- `:infrastructure` contains the public client, provider adapters, Ktor
  transport, MCP client, and local JSON Schema validation. It is published as
  `kmp`.

To verify all targets on Apple Silicon, use JDK 21, Android SDK 37, and Xcode.

```sh
./gradlew compileCommonMainKotlinMetadata compileKotlinJvm compileAndroidMain \
  compileKotlinIosArm64 compileKotlinIosSimulatorArm64
./gradlew jvmTest testAndroidHostTest iosSimulatorArm64Test
```

For local publishing, run `./gradlew publishToMavenLocal` and add
`mavenLocal()` to the consumer's repositories.

### Release to Maven Central

Verify a namespace that covers `in.hrup.aiclient` in the Sonatype Central
Portal. Choose the project's distribution license and configure its name and
URL before releasing. See the [namespace verification guide](https://central.sonatype.org/register/namespace/).

Set these GitHub repository secrets and variables.

| Setting | Location | Value |
| --- | --- | --- |
| `CENTRAL_USERNAME` | Actions secret | Central Portal user token username |
| `CENTRAL_PASSWORD` | Actions secret | Central Portal user token password |
| `CENTRAL_SIGNING_KEY` | Actions secret | ASCII-armored PGP private key for release signing |
| `CENTRAL_SIGNING_PASSWORD` | Actions secret | Passphrase for the signing key, if required |
| `CENTRAL_LICENSE_NAME` | Actions variable | Name of the project's distribution license |
| `CENTRAL_LICENSE_URL` | Actions variable | URL of the project's distribution license |

Publish the corresponding public signing key to a [supported keyserver](https://central.sonatype.org/publish/requirements/gpg/).
Set a new release version in `aiClientVersion` in `gradle.properties`, then
select **Actions → Publish aiClient to Maven Central → Run workflow**.
The workflow runs JVM tests, signs the Maven publications, and uploads one
bundle with `core`, `kmp`, and their JVM, Android, iOS arm64, and iOS
Simulator arm64 variants. It reports success when Central Portal reports
`PUBLISHED`. Use the published version in consumer projects once it appears
in Maven Central.

## License

aiClient is licensed under the [Apache License, Version 2.0](LICENSE).
