package `in`.hrup.aiclient.infrastructure.providers.text

import `in`.hrup.aiclient.infrastructure.structured.toJson
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiConversationItem
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.conversation.AiToolCall
import `in`.hrup.aiclient.model.conversation.AiToolResult
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.tool.AiTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun chatWireMessage(role: String, content: JsonElement): JsonObject =
    buildJsonObject {
        put("role", role)
        put("content", content)
    }

internal fun AiTool.toChatTool(): JsonObject = buildJsonObject {
    put("type", "function")
    put("function", buildJsonObject {
        put("name", name)
        put("description", description)
        put("parameters", inputSchema.toJson())
    })
}

internal fun AiToolCall.toChatCall(arguments: String = this.arguments.toJson().toString()): JsonObject =
    buildJsonObject {
        put("id", id)
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        })
    }

/** Keeps each complete assistant tool message intact for provider reasoning replay. */
internal class ChatToolHistory(
    val maxContextBytes: Int?,
    private val includeToolResultName: Boolean,
) {
    private val history = mutableMapOf<Int, ChatReplay>()
    private var retainedBytes = 0

    fun messages(request: AiRequest, encode: (AiMessage) -> JsonObject): List<JsonObject> = buildList {
        val names = request.input.filterIsInstance<AiToolCall>().associate { it.id to it.name }
        var index = 0
        while (index < request.input.size) {
            val turn = history[index]
            if (turn != null && request.input.subList(index, minOf(request.input.size, index + turn.items.size)) == turn.items) {
                add(turn.message)
                index += turn.items.size
                continue
            }
            when (val item = request.input[index++]) {
                is AiMessage -> add(encode(item))
                is AiToolCall -> {
                    val assistant = lastOrNull()?.takeIf { it.text("role") == "assistant" }
                    if (assistant != null) removeAt(lastIndex)
                    val message = assistant ?: chatWireMessage("assistant", JsonNull)
                    val calls = (message["tool_calls"] as? JsonArray).orEmpty() + item.toChatCall()
                    add(JsonObject(message + ("tool_calls" to JsonArray(calls))))
                }
                is AiToolResult -> add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", item.callId)
                    if (includeToolResultName) {
                        put("name", requireNotNull(names[item.callId]) { "Missing call for tool result" })
                    }
                    put("content", item.toFunctionOutput())
                })
            }
        }
    }

    fun remember(index: Int, items: List<AiConversationItem>, message: JsonObject) {
        if (maxContextBytes == null) return
        val bytes = message.toString().utf8Size()
        check(bytes <= maxContextBytes - retainedBytes) { "Chat tool continuation exceeds the context size limit" }
        history[index] = ChatReplay(items, message)
        retainedBytes += bytes
    }

    private class ChatReplay(val items: List<AiConversationItem>, val message: JsonObject)
}

internal fun TextHttpResponse.chatToolOutput(
    text: String?,
    calls: List<ChatFunctionCall>,
): List<AiConversationItem> = buildList {
    if (!text.isNullOrEmpty()) add(AiMessage(AiRole.ASSISTANT, listOf(AiContentPart.Text(text))))
    calls.forEach { call ->
        if (call.type != null && call.type != "function") fail("Unsupported chat tool call type")
        add(AiToolCall(
            call.id ?: fail("Missing chat tool call ID"),
            call.function?.name ?: fail("Missing chat tool name"),
            parseFunctionArguments(call.function.arguments ?: fail("Missing chat function arguments")),
        ))
    }
}

internal class ChatToolDeltas(private val maxContextBytes: Int?) {
    private val calls = mutableMapOf<Int, Call>()
    private val thinking = StringBuilder()
    private var bytes = 0L

    fun add(delta: ChatMessage) {
        delta.reasoningContent?.let { thinking.append(checked(it)) }
        delta.toolCalls.forEach { chunk ->
            val index = chunk.index ?: error("Missing streamed chat tool index")
            val call = calls.getOrPut(index) { Call() }
            chunk.id?.let { id ->
                if (id != call.id.toString()) call.id.append(checked(id))
            }
            chunk.type?.let {
                check(it == "function") { "Unsupported chat tool call type" }
            }
            chunk.function?.name?.let { call.name.append(checked(it)) }
            chunk.function?.arguments?.let { call.arguments.append(checked(it)) }
        }
    }

    fun calls(): List<ChatFunctionCall> = calls.keys.sorted().map { index ->
        val call = calls.getValue(index)
        ChatFunctionCall(
            id = call.id.toString().takeIf { it.isNotBlank() },
            type = "function",
            function = ChatFunction(call.name.toString(), call.arguments.toString()),
        )
    }

    fun message(text: String, output: List<AiConversationItem>, calls: List<ChatFunctionCall>): JsonObject =
        buildJsonObject {
            put("role", "assistant")
            put("content", text.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
            if (thinking.isNotEmpty()) put("reasoning_content", thinking.toString())
            val wireCalls = output.filterIsInstance<AiToolCall>().zip(calls).map { (call, completed) ->
                call.toChatCall(requireNotNull(completed.function?.arguments))
            }
            put("tool_calls", JsonArray(wireCalls))
        }

    private fun checked(value: String): String {
        bytes += value.utf8Size()
        check(maxContextBytes == null || bytes <= maxContextBytes) { "Streamed chat tool data exceeds the context size limit" }
        return value
    }

    private class Call {
        val id = StringBuilder()
        val name = StringBuilder()
        val arguments = StringBuilder()
    }
}

@Serializable
internal data class ChatFunctionCall(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: ChatFunction? = null,
)

@Serializable
internal data class ChatFunction(
    val name: String? = null,
    val arguments: String? = null,
)
