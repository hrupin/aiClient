package `in`.hrup.aiclient.infrastructure.providers.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ResponsesRequest(
    val model: String,
    val input: List<ResponsesInputMessage>,
    val stream: Boolean = false,
    val text: ResponsesText? = null,
)

@Serializable
internal data class ResponsesText(val format: ResponsesJsonFormat)

@Serializable
internal data class ResponsesJsonFormat(
    val type: String,
    val name: String,
    val strict: Boolean,
    val schema: JsonObject,
)

@Serializable
internal data class ResponsesInputMessage(val role: String, val content: JsonElement)

@Serializable
internal data class ResponsesResponse(
    val status: String,
    val output: List<ResponsesOutputItem>,
    val usage: ResponsesUsage? = null,
    val error: ResponsesError? = null,
    @SerialName("incomplete_details") val incompleteDetails: ResponsesIncompleteDetails? = null,
)

@Serializable
internal data class ResponsesOutputItem(
    val type: String,
    val content: List<ResponsesContent>? = null,
)

@Serializable
internal data class ResponsesContent(
    val type: String,
    val text: String? = null,
    val refusal: String? = null,
)

@Serializable
internal data class ResponsesUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null,
)

@Serializable
internal data class ResponsesErrorEnvelope(val error: ResponsesError)

@Serializable
internal data class ResponsesError(val message: String, val code: String? = null)

@Serializable
internal data class ResponsesIncompleteDetails(val reason: String? = null)

@Serializable
internal data class ResponsesStreamEvent(
    val type: String,
    @SerialName("output_index") val outputIndex: Int? = null,
    @SerialName("content_index") val contentIndex: Int? = null,
    val delta: String? = null,
    val item: ResponsesOutputItem? = null,
    val response: ResponsesResponse? = null,
    val message: String? = null,
    val code: String? = null,
)
