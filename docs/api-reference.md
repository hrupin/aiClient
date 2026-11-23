# aiClient API reference

For installation, dependency injection, and runnable examples, see the
[README](../README.md). This reference describes the current Kotlin API and
the behavior of its provider and MCP adapters.

## Client and configuration

| Type | Contract |
| --- | --- |
| `AiClient(httpClient, config)` | Public entry point for text generation and Local MCP. The application owns and closes the supplied Ktor `HttpClient`. |
| `AiClientConfig` | Holds the default provider, provider settings keyed by `AiProviderId`, and MCP connection settings keyed by application-defined server IDs. |
| `AiProviderConfig` | Holds the API key, optional default model, and optional full endpoint URL. |
| `McpServerConfig` | Holds the endpoint and connection headers for one MCP server. |
| `AiProviderId` | Identifies OpenAI, DeepSeek, xAI, Z.ai, Kimi, Perplexity, or BFL. Its `apiKeySetting` property gives the key setting name used in diagnostics. |

These types are in the `in.hrup.aiclient` package. Kotlin imports escape the
first segment as `` `in` ``.

The application injects keys. Creating an `AiClient` does not require keys
for every provider. A missing or blank key fails when the selected provider
is used, before the provider or a configured MCP server receives a request.

The optional [HTTP client factory](../infrastructure/src/commonMain/kotlin/in/hrup/aiclient/infrastructure/http/HttpClientFactory.kt)
uses platform Ktor engines, disables redirects, and sets connection and socket
timeouts of 10 and 60 seconds. Pass a Ktor client from the application's DI
container when it already owns transport configuration.

## Generation

| Call | Result | When work starts |
| --- | --- | --- |
| `generate(prompt, provider, model, responseFormat, mcpTools)` | `AiResponse` | On the suspend call |
| `generate(request, provider, mcpTools)` | `AiResponse` | On the suspend call |
| `stream(prompt, provider, model, responseFormat, mcpTools)` | `Flow<AiStreamEvent>` | On collection |
| `stream(request, provider, mcpTools)` | `Flow<AiStreamEvent>` | On collection |

All four overloads use the configured default provider when `provider` is
omitted. Prompt overloads build a single `USER` message and resolve the model
from the explicit model ID, the configured provider default, or the built-in
provider default, in that order. Request overloads take the model, input, and
response format from `AiRequest`.

Each collection starts a new request. If MCP tools are selected for that
request, collecting again can cause a tool to run again. Cancel the collecting
coroutine when the caller no longer needs the result.

### Conversation input

`AiRequest.input` is an ordered list of conversation items. Provider adapters
accept text in `AiMessage` items. Use `SYSTEM` for durable
instructions, `USER` for user turns, and `ASSISTANT` for previous assistant
turns. `DEVELOPER` is adapter- and model-dependent. The built-in chat request
mapping for Kimi, Z.ai, xAI, and Perplexity rejects that role, as does
DeepSeek when using `deepseek-v4-pro`.

The application stores conversation history and supplies it on each turn.
Append assistant messages from the final response once. For a streamed turn,
use the message in `Completed.response.output` as the history entry.

Image input support varies by model. The adapters accept
`AiContentPart.Image` alongside text for image-capable models. Use
`AiImageSource.Url` for a publicly reachable image on endpoints that accept
URLs, or `AiImageSource.Base64` for raw Base64 and a media type. For Kimi,
aiClient downloads URL inputs and sends the image inline as Base64. The
download accepts supported image media types up to 32 MiB per image.
DeepSeek images require `deepseek-flash` and are accepted in
user, system, and developer messages. For models and sources covered by
the tests, see the [image analysis examples](../README.md#image-analysis).
The image input works for buffered and streamed responses, with or without
JSON Schema.

The current adapters expect an empty `AiRequest.tools` list. Use the
`mcpTools` argument for Local MCP.

### Output and events

| Value | Meaning |
| --- | --- |
| `AiResponse.content` | Final assistant text, including the validated JSON text in Structured Output mode. |
| `AiResponse.request` | The JSON body sent to the provider for the final generation through `AiClient`. |
| `AiResponse.response` | The provider's response body. Streamed responses retain the SSE body received through completion. |
| `AiResponse.provider`, `AiResponse.model` | Provider ID and requested model ID for calls through `AiClient`. |
| `AiResponse.tokensSent`, `AiResponse.tokensReceived` | Reported input and output token counts for final generation. Missing counts default to zero here and remain null in `usage`. |
| `AiResponse.output` | Provider output mapped to conversation items. Text is in the `AiContentPart.Text` parts of assistant messages. |
| `AiResponse.usage` | Provider-reported counts when available. The usage object and its individual counters may be null. |
| `AiResponse.structuredOutput` | The validated JSON object as an `AiValue.Object` when a response format was requested. |
| `TextDelta` | An incremental text fragment. Output and content indices are zero based. |
| `OutputItemCompleted` | The mapped item at its output index. |
| `Completed` | The final response. A successful stream emits it once as the last event. |

A collection can emit text before a subsequent error. Wait for `Completed`
before treating a streamed structured result as valid.

### Provider exchange capture

The capture fields are populated for successful calls through `AiClient`.
`request` contains the provider-specific JSON body, including fields such as
messages, model ID, schema configuration, and streaming options when sent.
`response` retains fields in the provider body even when the adapter does not
map them into `output` or `usage`. For streaming, it retains the received SSE
body through completion, including the event framing. Use `content` for the
assembled assistant result and `structuredOutput` for a typed JSON object.

For Local MCP, the final generation body includes the tool result when a
tool ran. The model's separate tool-selection exchange, MCP server traffic,
and HTTP headers are outside the capture fields. Token counters refer to the
final generation. Inspect `usage` to distinguish a reported zero from an
absent counter.

An errored or cancelled stream has no successful `Completed` response to log
through this API. The capture retains the response body in memory, including
received SSE frames. Apply your application's retention and redaction rules
before persisting bodies that may contain prompt, completion, or tool data.

## Structured Output

Supply a `JsonResponseFormat` through either prompt overload or through
`AiRequest.responseFormat`. Use `parseJsonResponseFormat(name, definition)`
in the `in.hrup.aiclient.infrastructure.structured` package to construct it
from JSON Schema text. The response root must be a JSON object. Object
properties can contain nested objects, arrays, strings, numbers, booleans,
and nulls when the schema permits them.

OpenAI, DeepSeek, xAI, Perplexity, and `kimi-k3` receive a schema through the
provider API. Z.ai and the other Kimi models use JSON mode with the schema
in the model instructions. Provider restrictions may vary by model and
schema. aiClient validates successful structured responses locally in every
mode.

For `kimi-k3`, the adapter can unwrap an array containing exactly one
object and validates that object against the original schema. During SSE
streaming the adapter waits to emit the normalized object when it encounters
an array wrapper. Validation still rejects objects that violate the schema
and arrays that do not match the wrapper pattern.

`AiValue` is a Kotlin representation of JSON values. Object members are
available in `fields`, array elements in `values`, and JSON numbers in
`AiValue.Number.literal`, which preserves the numeric spelling without a
floating-point conversion. The structured value is available in the
returned `AiResponse` or in `Completed.response`.

## Local MCP

At the facade, `mcpTools` is a map of server IDs to tool-name sets. Each call
chooses its own tool exposure. Empty sets are ignored. When all sets are empty,
no MCP requests are made. An unknown server ID with a nonempty tool set
fails before discovery.

```kotlin
val tools = mapOf(
    "catalog" to setOf("get_available_models"),
    "support" to setOf("lookup_ticket"),
)
val response = ai.generate(
    prompt = "Look up the requested information.",
    mcpTools = tools,
)
```

Both server IDs in this example must exist in `AiClientConfig.mcpServers`.
A requested name can only become available when the server lists it.
Use distinct tool names across selected servers.

1. aiClient resolves the provider and checks its key.
2. The MCP client discovers tools with `tools/list` and keeps only the
   names selected for this request.
3. If any tools are available, a structured model request selects at most
   one tool and its arguments. The model may decline to use a tool.
4. aiClient checks the selected name and validates arguments against the
   tool's input schema, then calls `tools/call`.
5. The selected provider generates the final answer with the tool result as
   context. The caller's response schema applies to this final answer.

All four combinations of buffered or streamed output, with or without a
response schema, use this same MCP flow. The final stream begins after
discovery, tool selection, and tool execution. The application should derive
each request's allowed tools from its own authorization rules.

### Direct MCP API

| Entry point | Behavior |
| --- | --- |
| `StreamableHttpMcpClient.listTools()` | Returns allowlisted definitions and follows pagination cursors. |
| `StreamableHttpMcpClient.callTool(name, arguments)` | Calls an allowlisted tool, returning the MCP result as an `AiValue.Object`. |
| `generateWithMcpTools()`, `streamWithMcpTools()` | Let the model choose from explicit `McpAvailableTool` definitions backed by MCP clients. |
| `generateWithMcp()`, `streamWithMcp()` | Execute explicit `McpToolInvocation` entries in supplied order before final generation. |

The Streamable HTTP implementation opens a session per operation. It
negotiates a protocol revision, sends `notifications/initialized`, accepts
JSON or SSE replies, and attempts to terminate server-assigned sessions.
The application owns the underlying HTTP client.

The public facade uses Local MCP. The opt-in OpenAI Remote MCP integration
test covers provider-managed MCP separately.

## Image generation

`AiClient.generateImage(prompt, model, outputFormat, width, height)` uses Black Forest Labs.
The overload with `images` accepts ordered
`AiImageSource.Url` or `AiImageSource.Base64` references for editing.
FLUX 3 receives the references in `images` and accepts up to ten.
FLUX.2 uses `input_image`, `input_image_2`, and subsequent fields, with
a limit of eight references or four for FLUX.2 Klein. Base64 data is sent
as a plain string. An empty list generates an image from the prompt.
Set `outputFormat` to `AiImageFormat.PNG` or `AiImageFormat.JPEG` for
generation or editing with FLUX 3 Image Klein and FLUX.2. An explicit format
with `flux-3-image` raises `IllegalArgumentException` before submission.
Omit the argument to use the provider default.

The client checks the `BFL_API_KEY` configuration before submitting a request. The
model argument overrides `AiProviderConfig.defaultModel`, which defaults to
`flux-3-image`. The endpoint setting is a base URL ending in `/v1`, with
the model ID appended by the adapter.

The call submits a job and polls the returned URL until BFL reports
`Ready`. The overall timeout is 120 seconds. Cancelling the coroutine stops
local polling. A provider HTTP error, terminal task error, or invalid API
response raises `BflImageException` with the HTTP status and response body.

### Image dimensions

Both overloads accept optional integer `width` and `height` in pixels for
generation and editing. Omit both to let BFL choose the dimensions. For
FLUX 3, supplying only one dimension raises `IllegalArgumentException`.

For FLUX 3 Image and FLUX 3 Image Klein, the client clamps each side to the
model's range and rounds to the nearest multiple of 16, rounding ties up.
If the area exceeds the limit, it scales both sides down proportionally
and rounds them down to multiples of 16. For FLUX 3 Image, it then shortens
the longer side if needed to meet the 8:1 aspect ratio limit. Read
`AiImageResponse.request` to see the dimensions sent to BFL.

| Model | Pixels per side | Output limits |
| --- | --- | --- |
| `flux-3-image` | 256–6256 | Up to 16,822,272 pixels, or 4,194,304 with references. The longer side can be at most 8 times the shorter side. [BFL size guide](https://docs.bfl.ml/guides/prompting_unified_technical.md#resolution), [API reference](https://docs.bfl.ml/api-reference/utility/generate-an-image-with-flux-3.md) |
| `flux-3-image-klein` | 64–6256 | Up to 16,822,272 pixels. [BFL Klein API reference](https://docs.bfl.ml/api-reference/utility/generate-an-image-with-flux-3-klein.md) |
| FLUX.2 | At least 64 | BFL advertises up to 4 MP output. The client checks the per-side minimum and forwards each supplied dimension as given. [BFL model overview](https://docs.bfl.ml/flux_2/flux2_overview.md), [OpenAPI schema](https://api.bfl.ai/openapi.json) |

| Request | Dimensions sent to BFL |
| --- | --- |
| `flux-3-image`, 1017 × 775 | 1024 × 768 |
| `flux-3-image-klein`, 7000 × 896 | 6256 × 896 |
| `flux-3-image-klein`, 512 × 32 | 512 × 64 |
| `flux-3-image`, 20 × 3000 | 256 × 2048 |
| `flux-3-image`, 4096 × 4096 with a reference image | 2048 × 2048 |

### Image response fields

| Field | Value |
| --- | --- |
| `provider`, `model` | Provider ID `bfl` and the requested FLUX model. |
| `requestId` | BFL task ID. |
| `imageUrl` | Temporary delivery URL for the generated or edited image. |
| `request` | The submission JSON, including any reference images, output format, and dimensions. |
| `response` | The original JSON body of the final polling response. |
| `costCredits` | Settled cost in BFL credits if reported, otherwise the price quoted at submission. Returns `null` if neither response includes a cost. |

The application downloads the result from `imageUrl` if it needs the bytes.

## Errors and diagnostics

| Symptom | Where to look |
| --- | --- |
| `MissingAiApiKeyException` | Inspect `provider` and its `apiKeySetting`. Supply the key through the application's configuration. |
| `StructuredOutputException` | Inspect `violations` for schema validation details. Invalid JSON or an incorrect root type can produce an empty list. In an MCP request this can also refer to tool selection or arguments. |
| `McpException` | Inspect the MCP operation and server error. Initialization must return a negotiated `protocolVersion`. A tool-level error also stops generation. |
| `BflImageException` | Inspect `httpStatus` and `responseBody` for BFL submission or polling failures. |
| Provider response exception | Inspect the HTTP status, request ID, and provider code when available. |
| Flow fails before `Completed` | Handle the error in the collecting coroutine. The generation has no successful final result. |

See the [test matrix instructions](../README.md#tests) for selecting one model
or running all catalog models. The default matrix uses a simulated MCP server
and live provider APIs. A test using a real MCP endpoint is selected with
`-PaiMcpUrl`.
