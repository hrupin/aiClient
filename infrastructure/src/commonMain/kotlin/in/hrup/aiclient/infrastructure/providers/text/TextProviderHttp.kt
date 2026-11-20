package `in`.hrup.aiclient.infrastructure.providers.text

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiUsage
import `in`.hrup.aiclient.model.generation.StructuredOutputException
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ProviderResponseException(
    val provider: String,
    val httpStatus: Int,
    val requestId: String?,
    val code: String?,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class TextProviderHttp(
    val provider: String,
    private val transport: HttpTransport,
    private val endpoint: String,
    private val apiKey: String?,
) {
    val json = Json { ignoreUnknownKeys = true }

    init {
        require(apiKey == null || apiKey.isNotBlank()) { "$provider API key must not be blank" }
    }

    suspend fun post(payload: String): TextHttpResponse {
        val response = transport.execute(endpoint) {
            method = HttpMethod.Post
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            apiKey?.let { bearerAuth(it) }
            setBody(payload)
        }
        val result = TextHttpResponse(
            provider,
            response.head.status.value,
            response.head.headers["x-request-id"] ?: response.head.headers["request-id"],
            response.body.decodeToString(),
        )
        if (result.status !in 200..299) {
            val error = try {
                json.parseToJsonElement(result.body) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
            result.failFrom(error, "$provider returned HTTP ${result.status}")
        }
        return result
    }
}

internal data class TextHttpResponse(
    val provider: String,
    val status: Int,
    val requestId: String?,
    val body: String,
) {
    fun fail(message: String, code: String? = null, cause: Throwable? = null): Nothing =
        throw ProviderResponseException(provider, status, requestId, code, message, cause)

    fun failFrom(envelope: JsonObject?, fallback: String, fallbackCode: String? = null): Nothing {
        val error = envelope?.get("error")
        val fields = error as? JsonObject
        val message = fields?.get("message").asText()
            ?: error.asText()
            ?: envelope?.get("message").asText()
        val code = fields?.get("code").asText() ?: envelope?.get("code").asText()
        fail(message ?: fallback, code ?: fallbackCode)
    }

    inline fun <reified T> decode(json: Json): T = try {
        json.decodeFromString(body)
    } catch (cause: SerializationException) {
        fail("Invalid $provider response payload", cause = cause)
    }
}

private fun JsonElement?.asText(): String? =
    (this as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content

@Serializable
internal data class TextMessage(val role: String, val content: String)

@Serializable
internal data class TextUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("completion_tokens") val completionTokens: Long? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null,
) {
    fun toAiUsage() = AiUsage(inputTokens ?: promptTokens, outputTokens ?: completionTokens, totalTokens)
}

internal fun AiRequest.textMessages(provider: String): List<TextMessage> {
    require(tools.isEmpty()) { "$provider tool calling is unavailable in this adapter" }
    require(input.isNotEmpty()) { "$provider input must contain a message" }
    return input.map { item ->
        require(item is AiMessage) { "$provider input must contain only messages" }
        require(item.role != AiRole.DEVELOPER) { "$provider text adapter supports system, user and assistant messages" }
        require(item.content.isNotEmpty()) { "$provider messages must contain text" }
        val content = item.content.joinToString("") { part ->
            require(part is AiContentPart.Text) { "$provider text adapter accepts text content only" }
            part.text
        }
        TextMessage(item.role.name.lowercase(), content)
    }
}

internal fun TextHttpResponse.toAiResponse(
    messages: List<List<String>>,
    usage: AiUsage?,
    structured: JsonStructuredOutput?,
): AiResponse {
    if (messages.isEmpty() || messages.any { it.isEmpty() }) fail("$provider response is missing output text")
    val output = messages.map { parts -> AiMessage(AiRole.ASSISTANT, parts.map(AiContentPart::Text)) }
    val structuredOutput = structured?.let { validator ->
        val message = messages.singleOrNull()
            ?: throw StructuredOutputException("Expected one $provider structured output message")
        validator.validate(message.joinToString(""))
    }
    return AiResponse(output, usage, structuredOutput)
}
