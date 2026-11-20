package `in`.hrup.aiclient.infrastructure.providers.text

import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.model.generation.AiResponse
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
    if (decoded.status != "completed" || (decoded.error != null && decoded.error != JsonNull)) {
        response.failFrom(
            response.decode<JsonObject>(json),
            "$provider response status: ${decoded.status}",
            decoded.incompleteDetails?.reason,
        )
    }

    val messages = decoded.output.mapNotNull { item ->
        when (item.type) {
            in metadataTypes -> null
            "message" -> (item.content ?: response.fail("$provider message is missing content")).map { part ->
                when (part.type) {
                    "output_text" -> part.text ?: response.fail("$provider response is missing output text")
                    "refusal" -> response.fail(part.refusal ?: "$provider refused the request", "refusal")
                    else -> response.fail("Unsupported $provider output content type: ${part.type}")
                }
            }
            else -> response.fail("Unsupported $provider output item type: ${item.type}")
        }
    }
    return response.toAiResponse(messages, decoded.usage?.toAiUsage(), structured)
}

@Serializable
private data class ResponsesEnvelope(
    val status: String,
    val output: List<ResponsesItem> = emptyList(),
    val error: JsonElement? = null,
    val usage: TextUsage? = null,
    @SerialName("incomplete_details") val incompleteDetails: IncompleteDetails? = null,
)

@Serializable
private data class ResponsesItem(val type: String, val content: List<ResponsesPart>? = null)

@Serializable
private data class ResponsesPart(val type: String, val text: String? = null, val refusal: String? = null)

@Serializable
private data class IncompleteDetails(val reason: String? = null)
