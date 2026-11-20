package `in`.hrup.aiclient.infrastructure.providers.text

import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal class ChatCompletionsText(private val http: TextProviderHttp) {
    suspend fun generate(request: AiRequest): AiResponse {
        val messages = request.textMessages(http.provider)
        val structured = request.responseFormat?.let(::JsonStructuredOutput)
        val payload = if (structured == null) {
            ChatRequest(request.model.id, messages)
        } else {
            val instruction = TextMessage(
                role = "system",
                content = "Return exactly one JSON object matching the following JSON Schema. " +
                    "Do not include Markdown or any text outside the JSON object.\n${structured.definition}",
            )
            ChatRequest(request.model.id, listOf(instruction) + messages, ChatResponseFormat("json_object"))
        }
        val response = http.post(http.json.encodeToString(payload))
        val decoded = response.decode<ChatResponse>(http.json)
        val choice = decoded.choices.singleOrNull()
            ?: response.fail("${http.provider} response must contain one choice")
        choice.message.refusal?.let { response.fail(it, "refusal") }
        if (choice.finishReason != "stop") {
            response.fail("${http.provider} response finished with ${choice.finishReason}", choice.finishReason)
        }
        val text = choice.message.content ?: response.fail("${http.provider} response is missing output text")
        return response.toAiResponse(listOf(listOf(text)), decoded.usage?.toAiUsage(), structured)
    }
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<TextMessage>,
    @SerialName("response_format") val responseFormat: ChatResponseFormat? = null,
)

@Serializable
private data class ChatResponseFormat(val type: String)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice>, val usage: TextUsage? = null)

@Serializable
private data class ChatChoice(
    @SerialName("finish_reason") val finishReason: String,
    val message: ChatMessage,
)

@Serializable
private data class ChatMessage(val content: String? = null, val refusal: String? = null)
