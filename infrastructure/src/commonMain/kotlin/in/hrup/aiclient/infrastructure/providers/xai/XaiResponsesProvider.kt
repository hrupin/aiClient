package `in`.hrup.aiclient.infrastructure.providers.xai

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.providers.text.TextMessage
import `in`.hrup.aiclient.infrastructure.providers.text.TextProviderHttp
import `in`.hrup.aiclient.infrastructure.providers.text.generateResponses
import `in`.hrup.aiclient.infrastructure.providers.text.streamResponses
import `in`.hrup.aiclient.infrastructure.providers.text.textMessages
import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.provider.StreamingAiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class XaiResponsesProvider(
    transport: HttpTransport,
    apiKey: String,
    endpoint: String = "https://api.x.ai/v1/responses",
) : StreamingAiProvider {
    private val http = TextProviderHttp("xAI", transport, endpoint, apiKey)
    private val metadataTypes = setOf("reasoning")

    override suspend fun generate(request: AiRequest): AiResponse {
        val structured = request.responseFormat?.let(::JsonStructuredOutput)
        val payload = request.toPayload(structured)
        return http.generateResponses(http.json.encodeToString(payload), structured, metadataTypes)
    }
    override fun stream(request: AiRequest): Flow<AiStreamEvent> = flow {
        val structured = request.responseFormat?.let(::JsonStructuredOutput)
        val payload = request.toPayload(structured).copy(stream = true)
        emitAll(http.streamResponses(http.json.encodeToString(payload), structured, metadataTypes))
    }

    private fun AiRequest.toPayload(structured: JsonStructuredOutput?): XaiRequest {
        val input = textMessages(http.provider)
        return XaiRequest(
            model = model.id,
            input = input,
            text = structured?.let { XaiText(XaiFormat("json_schema", requireNotNull(responseFormat).name, it.definition, strict = true)) },
        )
    }
}

@Serializable
private data class XaiRequest(
    val model: String,
    val input: List<TextMessage>,
    val text: XaiText? = null,
    val stream: Boolean = false,
)

@Serializable
private data class XaiText(val format: XaiFormat)

@Serializable
private data class XaiFormat(val type: String, val name: String, val schema: JsonObject, val strict: Boolean)
