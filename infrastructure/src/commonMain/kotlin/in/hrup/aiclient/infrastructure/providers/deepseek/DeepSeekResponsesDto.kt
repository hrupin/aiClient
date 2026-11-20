package `in`.hrup.aiclient.infrastructure.providers.deepseek

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class DeepSeekRequest(
    val model: String,
    val input: List<DeepSeekMessage>,
    val text: DeepSeekText? = null,
    val stream: Boolean = false,
)

@Serializable
internal data class DeepSeekMessage(val role: String, val content: JsonElement)

@Serializable
internal data class DeepSeekText(val format: DeepSeekFormat)

@Serializable
internal data class DeepSeekFormat(val type: String, val name: String, val schema: JsonObject)

@Serializable
internal data class DeepSeekResponse(
    val status: String,
    val output: List<DeepSeekOutput>,
    val usage: DeepSeekUsage? = null,
    val error: DeepSeekError? = null,
    @SerialName("incomplete_details") val incompleteDetails: DeepSeekIncompleteDetails? = null,
)

@Serializable
internal data class DeepSeekOutput(val type: String, val content: List<DeepSeekContent>? = null)

@Serializable
internal data class DeepSeekContent(val type: String, val text: String? = null, val refusal: String? = null)

@Serializable
internal data class DeepSeekUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null,
)

@Serializable
internal data class DeepSeekErrorEnvelope(val error: DeepSeekError)

@Serializable
internal data class DeepSeekError(val message: String, val code: String? = null)

@Serializable
internal data class DeepSeekIncompleteDetails(val reason: String? = null)
