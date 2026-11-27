package `in`.hrup.aiclient.infrastructure.providers.perplexity

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.providers.text.ResponsesToolCalling
import `in`.hrup.aiclient.infrastructure.providers.text.ResponsesToolDialect
import `in`.hrup.aiclient.infrastructure.providers.text.TextMessage
import `in`.hrup.aiclient.infrastructure.providers.text.hasToolConversation
import `in`.hrup.aiclient.infrastructure.providers.text.TextProviderHttp
import `in`.hrup.aiclient.infrastructure.providers.text.generateResponses
import `in`.hrup.aiclient.infrastructure.providers.text.streamResponses
import `in`.hrup.aiclient.infrastructure.providers.text.textMessages
import `in`.hrup.aiclient.infrastructure.providers.text.responsesContent
import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.provider.AiToolCapableProvider
import `in`.hrup.aiclient.provider.AiToolCapabilities
import `in`.hrup.aiclient.provider.StreamingAiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PerplexityAgentProvider(
    transport: HttpTransport,
    apiKey: String,
    endpoint: String = "https://api.perplexity.ai/v1/agent",
) : StreamingAiProvider, AiToolCapableProvider {
    override fun toolCapabilities(model: AiModel): AiToolCapabilities {
        val native = model.id == "anthropic/claude-sonnet-5-5"
        return AiToolCapabilities(structuredOutput = true, nativeToolCalling = native, streamingToolCalling = native)
    }

    private val http = TextProviderHttp("Perplexity", transport, endpoint, apiKey)
    private val metadataTypes = setOf("reasoning", "search_results")

    override fun openToolSession(maxContextBytes: Int): StreamingAiProvider = toolAdapter(maxContextBytes)

    private fun toolAdapter(maxContextBytes: Int? = null) =
        ResponsesToolCalling(http, ResponsesToolDialect.PERPLEXITY, maxContextBytes, ::builtInTools)

    override suspend fun generate(request: AiRequest): AiResponse {
        if (request.hasToolConversation()) return toolAdapter().generate(request)
        val structured = request.responseFormat?.let(::JsonStructuredOutput)
        val payload = request.toPayload(structured)
        return http.generateResponses(http.json.encodeToString(payload), structured, metadataTypes)
    }
    override fun stream(request: AiRequest): Flow<AiStreamEvent> = flow {
        if (request.hasToolConversation()) {
            emitAll(toolAdapter().stream(request))
            return@flow
        }
        val structured = request.responseFormat?.let(::JsonStructuredOutput)
        val payload = request.toPayload(structured).copy(stream = true)
        emitAll(http.streamResponses(http.json.encodeToString(payload), structured, metadataTypes))
    }

    private fun AiRequest.toPayload(structured: JsonStructuredOutput?): PerplexityRequest {
        val input = textMessages(http.provider, ::responsesContent)
        return PerplexityRequest(
            model = model.id,
            input = input,
            tools = builtInTools(model),
            responseFormat = structured?.let {
                PerplexityFormat("json_schema", PerplexitySchema(requireNotNull(responseFormat).name, it.definition))
            },
        )
    }
}

@Serializable
private data class PerplexityRequest(
    val model: String,
    val input: List<TextMessage>,
    val tools: List<JsonObject> = emptyList(),
    @SerialName("response_format") val responseFormat: PerplexityFormat? = null,
    val stream: Boolean = false,
)

@Serializable
private data class PerplexityFormat(
    val type: String,
    @SerialName("json_schema") val jsonSchema: PerplexitySchema,
)

@Serializable
private data class PerplexitySchema(val name: String, val schema: JsonObject)

private val webSearch = listOf(buildJsonObject { put("type", "web_search") })

private fun builtInTools(model: AiModel): List<JsonObject> =
    if (model.id == "perplexity/sonar") webSearch else emptyList()
