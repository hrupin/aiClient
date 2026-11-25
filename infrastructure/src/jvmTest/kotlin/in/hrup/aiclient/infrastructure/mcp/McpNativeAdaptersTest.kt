package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.providers.deepseek.DeepSeekResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.kimi.KimiChatProvider
import `in`.hrup.aiclient.infrastructure.providers.openai.OpenAiResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.perplexity.PerplexityAgentProvider
import `in`.hrup.aiclient.infrastructure.providers.xai.XaiResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.zai.ZaiChatProvider
import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.model.generation.AiUsage
import `in`.hrup.aiclient.model.tool.AiTool
import `in`.hrup.aiclient.model.value.AiValue
import `in`.hrup.aiclient.provider.StreamingAiProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpNativeAdaptersTest {
    private val schema = parseJsonResponseFormat(
        "lookup", """{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}""",
    ).schema
    private val arguments = AiValue.Object(mapOf("id" to AiValue.Number("42")))
    private val result = AiValue.Object(mapOf("formula" to AiValue.Text("41 + 1")))
    private val route = McpToolRoute(this, AiTool("lookup", "Look up an ID", schema), { assertEquals("lookup", it) }) { name, args ->
        assertEquals("lookup", name)
        assertEquals(arguments, args)
        result
    }
    private val options = McpToolOptions(strategy = McpToolStrategy.NATIVE_TOOL_CALLING)

    @Test
    fun responsesAdaptersReplayRawHistoryAndCorrelateFunctionOutputs() = runBlocking {
        val adapters = mapOf<String, (HttpTransport) -> StreamingAiProvider>(
            "gpt-6-luna" to { OpenAiResponsesProvider(it, "key") },
            "deepseek-flash" to { DeepSeekResponsesProvider(it, "key") },
            "grok-4.6" to { XaiResponsesProvider(it, "key") },
            "anthropic/claude-sonnet-5-5" to { PerplexityAgentProvider(it, "key") },
        )
        for ((model, adapter) in adapters) {
            var step = 0
            HttpClient(MockEngine { request ->
                val payload = request.payload()
                assertEquals("lookup", payload.items("tools").single().field("name"))
                assertEquals("function", payload.items("tools").single().field("type"))
                val perplexity = model.startsWith("anthropic/")
                if (model == "deepseek-flash") {
                    assertNull(payload["store"])
                } else {
                    assertEquals("false", payload.getValue("store").jsonPrimitive.content)
                    if (perplexity) assertNull(payload["include"])
                    else assertTrue("reasoning.encrypted_content" in payload.getValue("include").toString())
                }
                when (step++) {
                    0 -> respond(responsesTurn(reasoningItem, functionItem))
                    1 -> {
                        val input = payload.items("input")
                        if (perplexity) assertTrue(input.none { it.field("type") == "reasoning" })
                        else assertEquals(Json.parseToJsonElement(reasoningItem), input[input.size - 3])
                        assertEquals(Json.parseToJsonElement(functionItem), input[input.size - 2])
                        val output = input.last()
                        assertEquals("function_call_output", output.field("type"))
                        assertEquals("call-1", output.field("call_id"))
                        assertEquals("""{"formula":"41 + 1"}""", output.field("output"))
                        respond(responsesTurn(answerItem))
                    }
                    else -> error("Unexpected request")
                }
            }).use { client ->
                val response = adapter(HttpTransport(client)).runMcpConversation(request(model), listOf(route), options)
                assertEquals("Final answer", response.content)
                assertEquals(AiUsage(14, 6, 20), response.usage)
                assertEquals(2, step)
            }
        }
    }

    @Test
    fun responsesStreamAssemblesArgumentsAndHidesToolRoundText() = runBlocking {
        val item = """{"type":"function_call","call_id":"call-1","name":"lookup"}"""
        var step = 0
        HttpClient(MockEngine { request ->
            val payload = request.payload()
            val body = when (step++) {
                0 -> sse(
                    """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Looking up"}""",
                    """{"type":"response.output_item.added","output_index":1,"item":$item}""",
                    """{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"id\":"}""",
                    """{"type":"response.function_call_arguments.delta","output_index":1,"delta":"42}"}""",
                    """{"type":"response.output_item.done","output_index":1,"item":$item}""",
                    """{"type":"response.completed","response":${responsesTurn("""{"type":"message","content":[{"type":"output_text","text":"Looking up"}]}""", item)}}""",
                )
                1 -> {
                    assertEquals("""{"id":42}""", payload.items("input").first { it.field("type") == "function_call" }.field("arguments"))
                    sse(
                        """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Final "}""",
                        """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"answer"}""",
                        """{"type":"response.output_item.done","output_index":0,"item":$answerItem}""",
                        """{"type":"response.completed","response":${responsesTurn(answerItem)}}""",
                    )
                }
                else -> error("Unexpected request")
            }
            respond(body, headers = eventStream)
        }).use { client ->
            val provider = OpenAiResponsesProvider(HttpTransport(client), "key")
            val events = provider.streamMcpConversation(request("gpt-6-luna"), options) { listOf(route) }.toList()
            assertEquals(listOf("Final ", "answer"), events.filterIsInstance<AiStreamEvent.TextDelta>().map { it.text })
            assertEquals(1, events.filterIsInstance<AiStreamEvent.Completed>().size)
            assertEquals(AiUsage(14, 6, 20), (events.last() as AiStreamEvent.Completed).response.usage)
        }
    }

    @Test
    fun chatAdaptersAssembleNativeCallsAndReplayAssistantReasoning() = runBlocking {
        val adapters = mapOf<String, (HttpTransport) -> StreamingAiProvider>(
            "glm-5.3" to { ZaiChatProvider(it, "key") },
            "kimi-k3" to { KimiChatProvider(it, "key") },
        )
        for ((model, adapter) in adapters) {
            var step = 0
            HttpClient(MockEngine { request ->
                val payload = request.payload()
                val definition = payload.items("tools").single()
                assertEquals("lookup", definition.getValue("function").jsonObject.field("name"))
                assertEquals(model.startsWith("glm"), payload["tool_stream"]?.jsonPrimitive?.content == "true")
                val body = when (step++) {
                    0 -> sse(
                        """{"choices":[{"index":0,"delta":{"reasoning_content":"retained ","content":"draft"}}]}""",
                        """{"choices":[{"index":0,"delta":{"reasoning_content":"reasoning","tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"look","arguments":"{\"id\":"}}]}}]}""",
                        """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"name":"up","arguments":"42}"}}]}}]}""",
                        """{"choices":[{"index":0,"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":4,"completion_tokens":3}}""",
                        "[DONE]",
                    )
                    1 -> {
                        val messages = payload.items("messages")
                        val assistant = messages[messages.size - 2]
                        val tool = messages.last()
                        assertEquals("assistant", assistant.field("role"))
                        assertEquals("retained reasoning", assistant.field("reasoning_content"))
                        assertEquals("call-1", assistant.items("tool_calls").single().field("id"))
                        assertEquals("tool", tool.field("role"))
                        assertEquals("call-1", tool.field("tool_call_id"))
                        assertEquals("lookup".takeIf { model == "kimi-k3" }, tool.field("name"))
                        assertEquals("""{"formula":"41 + 1"}""", tool.field("content"))
                        sse(
                            """{"choices":[{"index":0,"delta":{"content":"Final answer"}}]}""",
                            """{"choices":[{"index":0,"finish_reason":"stop"}],"usage":{"prompt_tokens":6,"completion_tokens":2}}""",
                            "[DONE]",
                        )
                    }
                    else -> error("Unexpected request")
                }
                respond(body, headers = eventStream)
            }).use { client ->
                val events = adapter(HttpTransport(client)).streamMcpConversation(request(model), options) { listOf(route) }.toList()
                assertEquals(listOf("Final answer"), events.filterIsInstance<AiStreamEvent.TextDelta>().map { it.text })
                assertFalse("draft" in events.toString())
                assertEquals(AiUsage(10, 5, 15), events.filterIsInstance<AiStreamEvent.Completed>().single().response.usage)
            }
        }
    }

    @Test
    fun openAiKeepsStructuredOutputInNativeToolRounds() = runBlocking {
        val format = parseJsonResponseFormat(
            "answer", """{"type":"object","properties":{"sum":{"type":"integer"}},"required":["sum"],"additionalProperties":false}""",
        )
        val answer = """{"type":"message","content":[{"type":"output_text","text":"{\"sum\":42}"}]}"""
        var step = 0
        HttpClient(MockEngine { request ->
            val payload = request.payload()
            assertEquals("answer", payload.getValue("text").jsonObject.getValue("format").jsonObject.field("name"))
            assertEquals("lookup", payload.items("tools").single().field("name"))
            respond(if (++step == 1) responsesTurn(functionItem) else responsesTurn(answer))
        }).use { client ->
            val provider = OpenAiResponsesProvider(HttpTransport(client), "key")
            val response = provider.runMcpConversation(
                request("gpt-6-luna").copy(responseFormat = format), listOf(route), options,
            )
            assertEquals(AiValue.Object(mapOf("sum" to AiValue.Number("42"))), response.structuredOutput)
            assertEquals(2, step)
        }
    }

    private fun request(model: String) =
        AiRequest(AiModel(model), listOf(AiMessage(AiRole.USER, listOf(AiContentPart.Text("Look up an ID")))))

    private fun responsesTurn(vararg items: String) =
        """{"status":"completed","output":[${items.joinToString(",")}],"usage":{"input_tokens":7,"output_tokens":3,"total_tokens":10}}"""

    private fun HttpRequestData.payload(): JsonObject =
        Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    private fun JsonObject.items(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    private fun JsonObject.field(key: String): String? = get(key)?.jsonPrimitive?.content

    private fun sse(vararg frames: String) = frames.joinToString("") { "data: $it\n\n" }

    private companion object {
        val eventStream = headersOf(HttpHeaders.ContentType, "text/event-stream")
        const val reasoningItem = """{"type":"reasoning","id":"encrypted-1","encrypted_content":"opaque","summary":[]}"""
        const val functionItem = """{"type":"function_call","call_id":"call-1","name":"lookup","arguments":"{\"id\":42}"}"""
        const val answerItem = """{"type":"message","content":[{"type":"output_text","text":"Final answer"}]}"""
    }
}
