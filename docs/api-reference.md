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
| `AiProviderId` | Identifies OpenAI, DeepSeek, xAI, Z.ai, Kimi, or Perplexity. Its `apiKeySetting` property gives the key setting name used in diagnostics. |

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

`AiRequest.input` is an ordered list of conversation items. The current
provider adapters accept text in `AiMessage` items. Use `SYSTEM` for durable
instructions, `USER` for user turns, and `ASSISTANT` for previous assistant
turns. `DEVELOPER` is adapter- and model-dependent. The built-in chat request
mapping for Kimi, Z.ai, xAI, and Perplexity rejects that role, as does
DeepSeek when using `deepseek-v4-pro`.

The application stores conversation history and supplies it on each turn.
Append assistant messages from the final response once. For a streamed turn,
use the message in `Completed.response.output` as the history entry.

The domain also defines image content, `AiTool`, `AiToolCall`, and
`AiToolResult`. The current text adapters expect text-only requests with
an empty `AiRequest.tools` list. Use the `mcpTools` argument for Local MCP.

### Output and events

| Value | Meaning |
| --- | --- |
| `AiResponse.output` | Provider output mapped to conversation items. Text is in the `AiContentPart.Text` parts of assistant messages. |
| `AiResponse.usage` | Provider-reported counts when available. The usage object and its individual counters may be null. |
| `AiResponse.structuredOutput` | The validated JSON object as an `AiValue.Object` when a response format was requested. |
| `TextDelta` | An incremental text fragment. Output and content indices are zero based. |
| `OutputItemCompleted` | The mapped item at its output index. |
| `Completed` | The final response. A successful stream emits it once as the last event. |

A collection can emit text before a subsequent error. Wait for `Completed`
before treating a streamed structured result as valid.

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

## Errors and diagnostics

| Symptom | Where to look |
| --- | --- |
| `MissingAiApiKeyException` | Inspect `provider` and its `apiKeySetting`. Supply the key through the application's configuration. |
| `StructuredOutputException` | Inspect `violations` for schema validation details. Invalid JSON or an incorrect root type can produce an empty list. In an MCP request this can also refer to tool selection or arguments. |
| `McpException` | Inspect the MCP operation and server error. Initialization must return a negotiated `protocolVersion`. A tool-level error also stops generation. |
| Provider response exception | Inspect the HTTP status, request ID, and provider code when available. |
| Flow fails before `Completed` | Handle the error in the collecting coroutine. The generation has no successful final result. |

See the [test matrix instructions](../README.md#tests) for selecting one model
or running all catalog models. The default matrix uses a simulated MCP server
and live provider APIs. A test using a real MCP endpoint is selected with
`-PaiMcpUrl`.
