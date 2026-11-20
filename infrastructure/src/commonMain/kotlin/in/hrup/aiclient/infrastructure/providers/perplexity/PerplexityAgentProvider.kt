package `in`.hrup.aiclient.infrastructure.providers.perplexity

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.providers.text.TextMessage
import `in`.hrup.aiclient.infrastructure.providers.text.TextProviderHttp
import `in`.hrup.aiclient.infrastructure.providers.text.generateResponses
import `in`.hrup.aiclient.infrastructure.providers.text.textMessages
import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.provider.AiProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class PerplexityAgentProvider(
    transport: HttpTransport,
    apiKey: String,
    endpoint: String = "https://api.perplexity.ai/v1/agent",
) : AiProvider {
    private val http = TextProviderHttp("Perplexity", transport, endpoint, apiKey)
    private val metadataTypes = setOf("reasoning", "search_results")

    override suspend fun generate(request: AiRequest): AiResponse {
        val input = request.textMessages(http.provider)
        val format = request.responseFormat
        val structured = format?.let(::JsonStructuredOutput)
        val payload = PerplexityRequest(
            model = request.model.id,
            input = input,
            responseFormat = structured?.let {
                PerplexityFormat("json_schema", PerplexitySchema(format.name, it.definition))
            },
        )
        return http.generateResponses(http.json.encodeToString(payload), structured, metadataTypes)
    }
}

@Serializable
private data class PerplexityRequest(
    val model: String,
    val input: List<TextMessage>,
    @SerialName("response_format") val responseFormat: PerplexityFormat? = null,
)

@Serializable
private data class PerplexityFormat(
    val type: String,
    @SerialName("json_schema") val jsonSchema: PerplexitySchema,
)

@Serializable
private data class PerplexitySchema(val name: String, val schema: JsonObject)
