package `in`.hrup.aiclient.infrastructure.providers.text

import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ChatCompletionsText(
    private val http: TextProviderHttp,
    private val nativeSchemaModels: Set<String> = emptySet(),
    private val allowsRemoteImageUrls: Boolean = true,
    private val supportsToolStreamParameter: Boolean = false,
    private val includeToolResultName: Boolean = false,
    private val maxContextBytes: Int? = null,
) {
    private val toolHistory = ChatToolHistory(maxContextBytes, includeToolResultName)

    fun toolSession(maxContextBytes: Int) = ChatCompletionsText(
        http, nativeSchemaModels, allowsRemoteImageUrls, supportsToolStreamParameter, includeToolResultName, maxContextBytes,
    )

    suspend fun generate(request: AiRequest, normalizeStructured: ((String) -> String)? = null): AiResponse {
        val structured = request.responseFormat?.let(::JsonStructuredOutput)
        val payload = request.toPayload(structured)
        val response = http.post(encode(payload))
        val decoded = response.decode<ChatResponse>(http.json)
        val choice = decoded.choices.singleOrNull()
            ?: response.fail("${http.provider} response must contain one choice")
        val message = response.copy(body = choice.message.toString()).decode<ChatMessage>(http.json)
        message.refusal?.let { response.fail(it, "refusal") }
        if (request.hasToolConversation() && choice.finishReason == "tool_calls" && message.toolCalls.isNotEmpty()) {
            val output = response.chatToolOutput(message.content, message.toolCalls)
            toolHistory.remember(request.input.size, output, choice.message)
            return AiResponse(output, decoded.usage?.toAiUsage())
        }
        if (choice.finishReason != "stop" || message.toolCalls.isNotEmpty()) {
            response.fail("${http.provider} response finished with ${choice.finishReason}", choice.finishReason)
        }
        val text = message.content ?: response.fail("${http.provider} response is missing output text")
        val output = if (structured != null && normalizeStructured != null) normalizeStructured(text) else text
        return response.toAiResponse(listOf(listOf(output)), decoded.usage?.toAiUsage(), structured)
    }

    fun stream(request: AiRequest, normalizeStructured: ((String) -> String)? = null): Flow<AiStreamEvent> = flow {
        val structured = request.responseFormat?.let(::JsonStructuredOutput)
        val payload = request.toPayload(structured).copy(
            stream = true,
            toolStream = true.takeIf { supportsToolStreamParameter && request.tools.isNotEmpty() },
        )
        val response = http.streamResponse()
        val text = StringBuilder()
        val toolDeltas = ChatToolDeltas(maxContextBytes).takeIf { request.hasToolConversation() }
        val normalize = normalizeStructured.takeIf { structured != null }
        // Wait for the first non-whitespace character before deciding whether to emit raw deltas.
        var streamAsReceived: Boolean? = if (normalize == null) true else null
        var usage: AiUsage? = null
        var finishReason: String? = null
        var textBytes = 0L
        var done = false
        http.stream(encode(payload)).takeWhile { frame ->
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
                if (finishReason != null || choice.index != 0) response.fail("Unexpected ${http.provider} streaming choice")
                choice.delta?.refusal?.let { response.fail(it, "refusal") }
                choice.finishReason?.let { reason ->
                    if (reason != "stop" && reason != "tool_calls") {
                        response.fail("${http.provider} stream finished with $reason", reason)
                    }
                    finishReason = reason
                }
                choice.delta?.let { toolDeltas?.add(it) }
                choice.delta?.content?.takeIf(String::isNotEmpty)?.let { delta ->
                    if (maxContextBytes != null) {
                        textBytes += delta.utf8Size()
                        if (textBytes > maxContextBytes) response.fail("Chat text exceeds the context size limit")
                    }
                    text.append(delta)
                    when (streamAsReceived) {
                        true -> emit(AiStreamEvent.TextDelta(0, 0, delta))
                        false -> Unit
                        null -> delta.firstOrNull { !it.isWhitespace() }?.let { first ->
                            streamAsReceived = first == '{'
                            if (first == '{') emit(AiStreamEvent.TextDelta(0, 0, text.toString()))
                        }
                    }
                }
            }
        }
        if (!done || finishReason == null) response.fail("${http.provider} stream ended before successful completion")
        val calls = toolDeltas?.calls().orEmpty()
        if (finishReason == "tool_calls") {
            if (calls.isEmpty() || toolDeltas == null) response.fail("Chat stream finished without requested tool calls")
            val output = response.chatToolOutput(text.toString(), calls)
            toolHistory.remember(request.input.size, output, toolDeltas.message(text.toString(), output, calls))
            output.forEachIndexed { index, item -> emit(AiStreamEvent.OutputItemCompleted(index, item)) }
            emit(AiStreamEvent.Completed(AiResponse(output, usage)))
            return@flow
        }
        if (calls.isNotEmpty()) response.fail("Chat stream returned tool calls without tool completion")
        if (text.isEmpty()) response.fail("${http.provider} stream is missing output text")
        val received = text.toString()
        val output = normalize?.invoke(received) ?: received
        val result = response.toAiResponse(listOf(listOf(output)), usage, structured)
        if (streamAsReceived != true) emit(AiStreamEvent.TextDelta(0, 0, output))
        emit(AiStreamEvent.OutputItemCompleted(0, result.output.single()))
        emit(AiStreamEvent.Completed(result))
    }

    private fun encode(payload: ChatRequest): String {
        val encoded = http.json.encodeToString(payload)
        check(maxContextBytes == null || encoded.utf8Size() <= maxContextBytes) {
            "Chat tool request exceeds the wire context size limit"
        }
        return encoded
    }

    private fun AiRequest.toPayload(structured: JsonStructuredOutput?): ChatRequest {
        val toolConversation = hasToolConversation()
        val messages = if (!toolConversation) {
            textMessages(http.provider) { chatContent(it, http.provider, allowsRemoteImageUrls) }
                .map { chatWireMessage(it.role, it.content) }
        } else {
            require(input.isNotEmpty()) { "${http.provider} input must contain a message" }
            toolHistory.messages(this) { message ->
                require(message.role != AiRole.DEVELOPER && message.content.isNotEmpty()) { "Invalid chat message" }
                chatWireMessage(message.role.name.lowercase(), chatContent(message.content, http.provider, allowsRemoteImageUrls))
            }
        }
        val request = ChatRequest(
            model.id,
            messages,
            tools = tools.map { it.toChatTool() }.takeIf { it.isNotEmpty() },
            toolChoice = "auto".takeIf { tools.isNotEmpty() },
            thinking = if (toolConversation && supportsToolStreamParameter) {
                buildJsonObject { put("clear_thinking", false) }
            } else null,
        )
        if (structured == null) return request
        if (model.id in nativeSchemaModels) {
            return request.copy(responseFormat = ChatResponseFormat(
                "json_schema", ChatJsonSchema(requireNotNull(responseFormat).name, structured.definition, strict = true),
            ))
        }
        val instruction = chatWireMessage(
            role = if (toolConversation) "user" else "system",
            content = JsonPrimitive(
                "Return exactly one JSON object matching the following JSON Schema. " +
                    "Do not include Markdown or any text outside the JSON object.\n${structured.definition}",
            ),
        )
        return request.copy(
            messages = if (toolConversation) messages + instruction else listOf(instruction) + messages,
            responseFormat = ChatResponseFormat("json_object"),
        )
    }
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<JsonObject>,
    @SerialName("response_format") val responseFormat: ChatResponseFormat? = null,
    val stream: Boolean = false,
    val tools: List<JsonObject>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    @SerialName("tool_stream") val toolStream: Boolean? = null,
    val thinking: JsonObject? = null,
)

@Serializable
private data class ChatResponseFormat(
    val type: String,
    @SerialName("json_schema") val jsonSchema: ChatJsonSchema? = null,
)

@Serializable
private data class ChatJsonSchema(
    val name: String,
    val schema: JsonObject,
    val strict: Boolean,
)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice>, val usage: TextUsage? = null)

@Serializable
private data class ChatChoice(
    @SerialName("finish_reason") val finishReason: String,
    val message: JsonObject,
)

@Serializable
internal data class ChatMessage(
    val content: String? = null,
    val refusal: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ChatFunctionCall> = emptyList(),
)

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
