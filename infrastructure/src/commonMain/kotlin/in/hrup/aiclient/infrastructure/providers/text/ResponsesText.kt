package `in`.hrup.aiclient.infrastructure.providers.text

import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal suspend fun TextProviderHttp.generateResponses(
    payload: String,
    structured: JsonStructuredOutput?,
    metadataTypes: Set<String>,
): AiResponse {
    val response = post(payload)
    val decoded = response.decode<ResponsesEnvelope>(json)
    return response.toResponsesResult(decoded, structured, metadataTypes)
}

internal fun TextProviderHttp.streamResponses(
    payload: String,
    structured: JsonStructuredOutput?,
    metadataTypes: Set<String>,
): Flow<AiStreamEvent> = flow {
    val indices = mutableMapOf<Int, Int>()
    val response = streamResponse()
    var completed = false
    stream(payload).transformWhile { frame ->
        val eventResponse = streamResponse(frame.data)
        val event = eventResponse.decode<ResponsesStreamingEvent>(json)
        fun missing(field: String): Nothing = response.fail("Missing $field in $provider ${event.type}")
        fun messageIndex(): Int = indices.getOrPut(event.outputIndex ?: missing("output_index")) { indices.size }

        when (event.type) {
            "response.output_item.added" -> {
                if ((event.item ?: missing("item")).type == "message") messageIndex()
            }
            "response.output_text.delta" -> emit(AiStreamEvent.TextDelta(
                messageIndex(), event.contentIndex ?: missing("content_index"), event.delta ?: missing("delta"),
            ))
            "response.output_item.done" -> {
                val text = response.outputText(event.item ?: missing("item"), metadataTypes)
                if (text != null) {
                    emit(AiStreamEvent.OutputItemCompleted(
                        messageIndex(),
                        AiMessage(AiRole.ASSISTANT, text.map(AiContentPart::Text)),
                    ))
                }
            }
            "response.completed" -> {
                val result = response.toResponsesResult(event.response ?: missing("response"), structured, metadataTypes)
                completed = true
                emit(AiStreamEvent.Completed(result))
            }
            "response.incomplete", "response.failed" -> response.failFrom(
                (event.response ?: missing("response")).errorEnvelope(),
                "$provider stream finished with ${event.type}",
                event.response.incompleteDetails?.reason,
            )
            "error" -> eventResponse.failFrom(
                eventResponse.decode<JsonObject>(json),
                "$provider streaming error",
            )
        }
        !completed
    }.collect { emit(it) }
    if (!completed) response.fail("$provider stream ended before response.completed")
}

private fun TextHttpResponse.toResponsesResult(
    decoded: ResponsesEnvelope,
    structured: JsonStructuredOutput?,
    metadataTypes: Set<String>,
): AiResponse {
    if (decoded.status != "completed" || (decoded.error != null && decoded.error != JsonNull)) {
        failFrom(
            decoded.errorEnvelope(),
            "$provider response status: ${decoded.status}",
            decoded.incompleteDetails?.reason,
        )
    }

    val messages = decoded.output.mapNotNull { outputText(it, metadataTypes) }
    val sources = decoded.output.filter { it.type == "search_results" }.flatMap { it.results.toSearchSources() }
    return toAiResponse(messages, decoded.usage?.toAiUsage(), structured).copy(searchSources = sources)
}

private fun TextHttpResponse.outputText(item: ResponsesItem, metadataTypes: Set<String>): List<String>? =
    when (item.type) {
        in metadataTypes -> null
        "message" -> (item.content ?: fail("$provider message is missing content")).map { part ->
            when (part.type) {
                "output_text" -> part.text ?: fail("$provider response is missing output text")
                "refusal" -> fail(part.refusal ?: "$provider refused the request", "refusal")
                else -> fail("Unsupported $provider output content type: ${part.type}")
            }
        }
        else -> fail("Unsupported $provider output item type: ${item.type}")
    }

@Serializable
private data class ResponsesStreamingEvent(
    val type: String,
    @SerialName("output_index") val outputIndex: Int? = null,
    @SerialName("content_index") val contentIndex: Int? = null,
    val delta: String? = null,
    val item: ResponsesItem? = null,
    val response: ResponsesEnvelope? = null,
)

@Serializable
private data class ResponsesEnvelope(
    val status: String,
    val output: List<ResponsesItem> = emptyList(),
    val error: JsonElement? = null,
    val usage: TextUsage? = null,
    @SerialName("incomplete_details") val incompleteDetails: IncompleteDetails? = null,
) {
    fun errorEnvelope(): JsonObject? = error?.let { JsonObject(mapOf("error" to it)) }
}

@Serializable
private data class ResponsesItem(
    val type: String,
    val content: List<ResponsesPart>? = null,
    val results: JsonElement? = null,
)

@Serializable
private data class ResponsesPart(val type: String, val text: String? = null, val refusal: String? = null)

@Serializable
private data class IncompleteDetails(val reason: String? = null)
