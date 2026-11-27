package `in`.hrup.aiclient.infrastructure.providers.text

import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.infrastructure.structured.toAiObject
import `in`.hrup.aiclient.infrastructure.structured.toJson
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiConversationItem
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.conversation.AiToolCall
import `in`.hrup.aiclient.model.conversation.AiToolResult
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.provider.StreamingAiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun AiRequest.hasToolConversation(): Boolean =
    tools.isNotEmpty() || input.any { it is AiToolCall || it is AiToolResult }

internal enum class ResponsesToolDialect(
    val encryptedReasoning: Boolean = false,
    val storeParameter: Boolean = false,
    val functionStrictParameter: Boolean = false,
) {
    OPENAI(encryptedReasoning = true, storeParameter = true, functionStrictParameter = true),
    XAI(encryptedReasoning = true, storeParameter = true, functionStrictParameter = true),
    DEEPSEEK,
    PERPLEXITY(storeParameter = true),
}

/** Responses wire format. Scoped instances retain provider-specific continuation data. */
internal class ResponsesToolCalling(
    private val http: TextProviderHttp,
    private val dialect: ResponsesToolDialect,
    private val maxContextBytes: Int? = null,
    private val builtInTools: (AiModel) -> List<JsonObject> = { emptyList() },
) : StreamingAiProvider {
    private val history = mutableMapOf<Int, Replay>()
    private var retainedWireBytes = 0

    override suspend fun generate(request: AiRequest): AiResponse {
        val response = http.post(payload(request, streaming = false))
        return response.toToolResponse(request, response.decode<ToolResponse>(http.json))
    }

    override fun stream(request: AiRequest): Flow<AiStreamEvent> = flow {
        val response = http.streamResponse()
        val arguments = ResponsesArgumentFragments(maxContextBytes)
        val indices = mutableMapOf<Int, Int>()
        var completed = false
        http.stream(payload(request, streaming = true)).transformWhile { frame ->
            val eventResponse = http.streamResponse(frame.data)
            val event = eventResponse.decode<ToolStreamEvent>(http.json)
            fun index() = event.outputIndex ?: response.fail("Missing tool stream output index")
            fun outputIndex() = indices.getOrPut(index()) { indices.size }
            fun item() = event.item ?: response.fail("Missing tool stream output item")
            fun terminal() = event.response ?: response.fail("Missing terminal tool response")

            when (event.type) {
                "response.output_item.added" -> {
                    when (item().text("type")) {
                        "function_call" -> {
                            outputIndex()
                            item().text("arguments")?.let { arguments.append(index(), it) }
                        }
                        "message" -> outputIndex()
                    }
                }
                "response.function_call_arguments.delta" ->
                    arguments.append(index(), event.delta ?: response.fail("Missing function argument delta"))
                "response.function_call_arguments.done" ->
                    arguments.finish(index(), event.arguments ?: response.fail("Missing completed function arguments"))
                "response.output_text.delta" -> emit(AiStreamEvent.TextDelta(
                    outputIndex(),
                    event.contentIndex ?: response.fail("Missing tool stream content index"),
                    event.delta ?: response.fail("Missing output text delta"),
                ))
                "response.output_item.done" -> {
                    val item = item()
                    val complete = if (item.text("type") == "function_call") arguments.complete(index(), item) else item
                    response.toItem(complete)?.let { emit(AiStreamEvent.OutputItemCompleted(outputIndex(), it)) }
                }
                "response.completed" -> {
                    val terminal = terminal()
                    val output = terminal.output.mapIndexed { index, item ->
                        if (item.text("type") == "function_call") arguments.complete(index, item) else item
                    }
                    completed = true
                    emit(AiStreamEvent.Completed(response.toToolResponse(request, terminal.copy(output = output))))
                }
                "response.incomplete", "response.failed" -> response.failFrom(
                    terminal().errorEnvelope(), "AI tool response status: ${terminal().status}", terminal().incompleteDetails?.reason,
                )
                "error" -> eventResponse.failFrom(eventResponse.decode<JsonObject>(http.json), "AI tool streaming error")
            }
            !completed
        }.collect { emit(it) }
        if (!completed) response.fail("AI tool stream ended before response.completed")
    }

    private fun payload(request: AiRequest, streaming: Boolean): String {
        require(request.input.isNotEmpty()) { "${http.provider} input must contain a message" }
        val encoded = buildJsonObject {
            put("model", request.model.id)
            put("input", buildJsonArray {
                var index = 0
                while (index < request.input.size) {
                    val replay = history[index]
                    if (replay != null && request.input.subList(index, minOf(request.input.size, index + replay.mapped.size)) == replay.mapped) {
                        replay.wire.forEach { add(it) }
                        index += replay.mapped.size
                    } else {
                        add(request.input[index++].toWire())
                    }
                }
            })
            if (streaming) put("stream", true)
            if (dialect.storeParameter) put("store", false)
            if (dialect.encryptedReasoning) put("include", buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) })
            val hostedTools = builtInTools(request.model)
            if (hostedTools.isNotEmpty() || request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    hostedTools.forEach { add(it) }
                    request.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.inputSchema.toJson())
                            if (dialect.functionStrictParameter) put("strict", false)
                        })
                    }
                })
            }
            request.responseFormat?.let { format ->
                val schema = buildJsonObject {
                    put("name", format.name)
                    put("schema", format.schema.toJson())
                }
                if (dialect == ResponsesToolDialect.PERPLEXITY) {
                    put("response_format", buildJsonObject {
                        put("type", "json_schema")
                        put("json_schema", schema)
                    })
                } else {
                    put("text", buildJsonObject {
                        put("format", buildJsonObject {
                            put("type", "json_schema")
                            schema.forEach { (key, value) -> put(key, value) }
                            if (dialect.functionStrictParameter) put("strict", true)
                        })
                    })
                }
            }
        }.toString()
        if (maxContextBytes != null && encoded.utf8Size() > maxContextBytes) {
            throw IllegalStateException("AI tool request exceeds the wire context size limit")
        }
        return encoded
    }

    private fun TextHttpResponse.toToolResponse(request: AiRequest, response: ToolResponse): AiResponse {
        if (response.status != "completed" || (response.error != null && response.error != JsonNull)) {
            failFrom(response.errorEnvelope(), "AI tool response status: ${response.status}", response.incompleteDetails?.reason)
        }
        val output = response.output.mapNotNull { toItem(it) }
        val calls = output.filterIsInstance<AiToolCall>()
        if (output.isEmpty()) fail("AI tool response is missing output")
        if (maxContextBytes != null && calls.isNotEmpty()) {
            val wire = if (dialect == ResponsesToolDialect.PERPLEXITY) {
                response.output.filter { it.text("type") == "message" || it.text("type") == "function_call" }
            } else {
                response.output
            }
            val bytes = wire.sumOf { it.toString().utf8Size().toLong() }
            if (bytes > maxContextBytes - retainedWireBytes) fail("AI tool continuation exceeds the context size limit")
            retainedWireBytes += bytes.toInt()
            history[request.input.size] = Replay(output, wire)
        }
        val structured = request.responseFormat?.takeIf { calls.isEmpty() }?.let {
            val message = output.singleOrNull() as? AiMessage ?: fail("Expected one structured output message")
            JsonStructuredOutput(it).validate(message.content.filterIsInstance<AiContentPart.Text>().joinToString("") { part -> part.text })
        }
        val sources = if (dialect == ResponsesToolDialect.PERPLEXITY) {
            response.output.filter { it.text("type") == "search_results" }.flatMap { it["results"].toSearchSources() }
        } else emptyList()
        return AiResponse(output, response.usage?.toAiUsage(), structured, searchSources = sources)
    }

    private fun TextHttpResponse.toItem(item: JsonObject): AiConversationItem? = when (item.text("type")) {
        "reasoning" -> null
        "search_results", "web_search_call", "fetch_url_call" ->
            if (dialect == ResponsesToolDialect.PERPLEXITY) null else fail("Unsupported AI tool response item")
        "message" -> {
            val content = item["content"] as? JsonArray ?: fail("AI response is missing message content")
            AiMessage(AiRole.ASSISTANT, content.map { part ->
                val fields = part as? JsonObject ?: fail("Invalid message content")
                when (fields.text("type")) {
                    "output_text" -> AiContentPart.Text(fields.text("text") ?: fail("Missing output text"))
                    "refusal" -> fail(fields.text("refusal") ?: "AI provider refused the request", "refusal")
                    else -> fail("Unsupported response content")
                }
            })
        }
        "function_call" -> AiToolCall(
            item.text("call_id") ?: fail("Missing function call ID"),
            item.text("name") ?: fail("Missing function name"),
            parseFunctionArguments(item.text("arguments") ?: fail("Missing function arguments")),
        )
        else -> fail("Unsupported AI tool response item")
    }

    private fun AiConversationItem.toWire(): JsonObject = buildJsonObject {
        when (val item = this@toWire) {
            is AiMessage -> {
                require(item.content.isNotEmpty()) { "AI messages must contain content" }
                if (dialect == ResponsesToolDialect.PERPLEXITY) put("type", "message")
                put("role", item.role.name.lowercase())
                put("content", responsesContent(item.content))
            }
            is AiToolCall -> {
                put("type", "function_call")
                put("call_id", item.id)
                put("name", item.name)
                put("arguments", item.arguments.toJson().toString())
            }
            is AiToolResult -> {
                put("type", "function_call_output")
                put("call_id", item.callId)
                put("output", item.toFunctionOutput())
            }
        }
    }

    private class Replay(val mapped: List<AiConversationItem>, val wire: List<JsonObject>)
}

internal fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun TextHttpResponse.parseFunctionArguments(arguments: String) = try {
    (Json.parseToJsonElement(arguments) as? JsonObject ?: fail("Function arguments must be a JSON object")).toAiObject(maxDepth = 64)
} catch (_: SerializationException) {
    fail("Invalid function argument JSON")
} catch (_: IllegalArgumentException) {
    fail("Invalid function argument data")
}

internal fun String.utf8Size(): Int = encodeToByteArray().size

internal fun AiToolResult.toFunctionOutput(): String {
    val text = content.filterIsInstance<AiContentPart.Text>().joinToString("\n") { it.text }
    if (structuredContent == null && !isError) return text
    if (content.isEmpty() && !isError) return structuredContent?.toJson().toString()
    return buildJsonObject {
        put("isError", isError)
        if (text.isNotEmpty()) put("text", text)
        structuredContent?.let { put("structuredContent", it.toJson()) }
    }.toString()
}

private class ResponsesArgumentFragments(private val limit: Int?) {
    private val fragments = mutableMapOf<Int, StringBuilder>()
    private val completed = mutableMapOf<Int, String>()
    private var bytes = 0L

    fun append(index: Int, delta: String) {
        check(index !in completed) { "Function argument delta followed completion" }
        bytes += delta.utf8Size()
        check(limit == null || bytes <= limit) { "Streamed function arguments exceed the context size limit" }
        fragments.getOrPut(index) { StringBuilder() }.append(delta)
    }

    fun finish(index: Int, arguments: String) {
        val partial = fragments.remove(index)?.toString()
        check(partial.isNullOrEmpty() || partial == arguments) { "Inconsistent streamed function arguments" }
        check(completed.put(index, arguments).let { it == null || it == arguments }) { "Conflicting streamed function arguments" }
    }

    fun complete(index: Int, item: JsonObject): JsonObject {
        val arguments = item.text("arguments")?.takeIf { it.isNotEmpty() }
            ?: completed[index] ?: fragments[index]?.toString()
            ?: error("Missing completed function arguments")
        finish(index, arguments)
        return JsonObject(item + ("arguments" to JsonPrimitive(arguments)))
    }
}

@Serializable
private data class ToolStreamEvent(
    val type: String,
    @SerialName("output_index") val outputIndex: Int? = null,
    @SerialName("content_index") val contentIndex: Int? = null,
    val delta: String? = null,
    val arguments: String? = null,
    val item: JsonObject? = null,
    val response: ToolResponse? = null,
)

@Serializable
private data class ToolResponse(
    val status: String,
    val output: List<JsonObject> = emptyList(),
    val error: JsonElement? = null,
    val usage: TextUsage? = null,
    @SerialName("incomplete_details") val incompleteDetails: ToolIncompleteDetails? = null,
) {
    fun errorEnvelope() = error?.let { JsonObject(mapOf("error" to it)) }
}

@Serializable
private data class ToolIncompleteDetails(val reason: String? = null)
