package `in`.hrup.aiclient.infrastructure.providers.xai

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.providers.text.TextMessage
import `in`.hrup.aiclient.infrastructure.providers.text.TextProviderHttp
import `in`.hrup.aiclient.infrastructure.providers.text.generateResponses
import `in`.hrup.aiclient.infrastructure.providers.text.textMessages
import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.provider.AiProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class XaiResponsesProvider(
    transport: HttpTransport,
    apiKey: String,
    endpoint: String = "https://api.x.ai/v1/responses",
) : AiProvider {
    private val http = TextProviderHttp("xAI", transport, endpoint, apiKey)
    private val metadataTypes = setOf("reasoning")

    override suspend fun generate(request: AiRequest): AiResponse {
        val input = request.textMessages(http.provider)
        val format = request.responseFormat
        val structured = format?.let(::JsonStructuredOutput)
        val payload = XaiRequest(
            model = request.model.id,
            input = input,
            text = structured?.let { XaiText(XaiFormat("json_schema", format.name, it.definition, strict = true)) },
        )
        return http.generateResponses(http.json.encodeToString(payload), structured, metadataTypes)
    }
}

@Serializable
private data class XaiRequest(val model: String, val input: List<TextMessage>, val text: XaiText? = null)

@Serializable
private data class XaiText(val format: XaiFormat)

@Serializable
private data class XaiFormat(val type: String, val name: String, val schema: JsonObject, val strict: Boolean)
