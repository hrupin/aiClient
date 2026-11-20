package `in`.hrup.aiclient.infrastructure.providers.text

import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.model.generation.AiUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal class ChatCompletionsText(private val http: TextProviderHttp) {
    suspend fun generate(request: AiRequest): AiResponse {
        val structured = request.responseFormat?.let(::JsonStructuredOutput)
        val payload = request.toPayload(structured)
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

    fun stream(request: AiRequest): Flow<AiStreamEvent> = flow {
        val structured = request.responseFormat?.let(::JsonStructuredOutput)
        val payload = request.toPayload(structured).copy(stream = true)
        val response = http.streamResponse()
        val text = StringBuilder()
        var usage: AiUsage? = null
        var stopped = false
        var done = false
        http.stream(http.json.encodeToString(payload)).takeWhile { frame ->
            done = frame.data == "[DONE]"
            !done
        }.collect { frame ->
            val event = http.streamResponse(frame.data)
            val chunk = event.decode<ChatStreamChunk>(http.json)
            if (chunk.error != null && chunk.error != JsonNull) {
                event.failFrom(event.decode<JsonObject>(http.json), "${http.provider} streaming error")
            }
            chunk.usage?.let { usage = it.toAiUsage() }
            for (choice in chunk.choices) {
                if (stopped || choice.index != 0) response.fail("Unexpected ${http.provider} streaming choice")
                choice.delta?.refusal?.let { response.fail(it, "refusal") }
                choice.finishReason?.let { reason ->
                    if (reason != "stop") response.fail("${http.provider} stream finished with $reason", reason)
                    stopped = true
                }
                choice.delta?.content?.takeIf(String::isNotEmpty)?.let { delta ->
                    text.append(delta)
                    emit(AiStreamEvent.TextDelta(0, 0, delta))
                }
            }
        }
        if (!done || !stopped) response.fail("${http.provider} stream ended before successful completion")
        if (text.isEmpty()) response.fail("${http.provider} stream is missing output text")
        val result = response.toAiResponse(listOf(listOf(text.toString())), usage, structured)
        emit(AiStreamEvent.OutputItemCompleted(0, result.output.single()))
        emit(AiStreamEvent.Completed(result))
    }

    private fun AiRequest.toPayload(structured: JsonStructuredOutput?): ChatRequest {
        val messages = textMessages(http.provider)
        return if (structured == null) {
            ChatRequest(model.id, messages)
        } else {
            val instruction = TextMessage(
                role = "system",
                content = "Return exactly one JSON object matching the following JSON Schema. " +
                    "Do not include Markdown or any text outside the JSON object.\n${structured.definition}",
            )
            ChatRequest(model.id, listOf(instruction) + messages, ChatResponseFormat("json_object"))
        }
    }
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<TextMessage>,
    @SerialName("response_format") val responseFormat: ChatResponseFormat? = null,
    val stream: Boolean = false,
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

@Serializable
private data class ChatStreamChunk(
    val choices: List<ChatStreamChoice> = emptyList(),
    val usage: TextUsage? = null,
    val error: JsonElement? = null,
)

@Serializable
private data class ChatStreamChoice(
    val index: Int,
    val delta: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)
