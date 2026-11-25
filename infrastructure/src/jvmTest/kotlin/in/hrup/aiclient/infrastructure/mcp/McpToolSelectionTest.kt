package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.McpServerConfig
import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.model.generation.StructuredOutputException
import `in`.hrup.aiclient.model.tool.AiTool
import `in`.hrup.aiclient.model.value.AiValue
import `in`.hrup.aiclient.provider.AiProvider
import `in`.hrup.aiclient.provider.StreamingAiProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class McpToolSelectionTest {
    @Test
    fun validatesEveryCallBeforeInvokingAnyTools() = runBlocking {
        HttpClient(MockEngine { error("Unexpected MCP request") }).use { http ->
            val client = StreamableHttpMcpClient(HttpTransport(http), "https://mcp.example", setOf("lookup"))
            val schema = parseJsonResponseFormat(
                "lookup",
                """{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}""",
            ).schema
            val tools = listOf(McpAvailableTool(client, AiTool("lookup", "Look up an ID", schema)))
            val request = AiRequest(AiModel("test"), emptyList())
            val validCall = "lookup" to """{"id":42}"""
            assertFailsWith<McpException> {
                planner(validCall, "unknown" to "{}").generateWithMcpTools(request, tools)
            }
            assertFailsWith<StructuredOutputException> {
                planner(validCall, "lookup" to """{"id":"invalid"}""").generateWithMcpTools(request, tools)
            }
        }
        Unit
    }

    @Test
    fun emptyCallArrayPassesTheOriginalRequestThrough() = runBlocking {
        HttpClient(MockEngine { error("Unexpected MCP request") }).use { http ->
            val client = StreamableHttpMcpClient(HttpTransport(http), "https://mcp.example", setOf("lookup"))
            val schema = parseJsonResponseFormat("lookup", """{"type":"object"}""").schema
            val tools = listOf(McpAvailableTool(client, AiTool("lookup", "Look up an ID", schema)))
            val request = AiRequest(AiModel("test"), emptyList())
            val finalResponse = AiResponse(output = emptyList())
            val provider = planner(onFinal = { actual ->
                assertSame(request, actual)
                finalResponse
            })
            assertSame(finalResponse, provider.generateWithMcpTools(request, tools))
        }
    }

    @Test
    fun discoveryReportsAnUnavailableRequestedTool() = runBlocking {
        HttpClient(MockEngine { request ->
            val payload = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            val result = when (payload.getValue("method").jsonPrimitive.content) {
                "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}}}"""
                "notifications/initialized" -> return@MockEngine respond("", HttpStatusCode.Accepted)
                "tools/list" -> """{"tools":[{"name":"lookup","inputSchema":{"type":"object"}}]}"""
                else -> error("Unexpected MCP call")
            }
            respond(
                """{"jsonrpc":"2.0","id":${payload["id"]},"result":$result}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }).use { http ->
            val discovery = McpToolDiscovery(
                HttpTransport(http), mapOf("test" to McpServerConfig("https://mcp.example")), McpToolOptions(),
            )
            val error = assertFailsWith<McpException> { discovery.discover(mapOf("test" to setOf("lookup", "missing"))) }
            assertTrue("did not list 1" in error.message.orEmpty())
        }
    }

    @Test
    fun explicitStreamingInvocationsKeepTheirOrderAndTheRequestedFormat() = runBlocking {
        val invoked = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            val payload = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            val result = when (payload.getValue("method").jsonPrimitive.content) {
                "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}}}"""
                "notifications/initialized" -> return@MockEngine respond("", HttpStatusCode.Accepted)
                "tools/call" -> {
                    val name = payload.getValue("params").jsonObject.getValue("name").jsonPrimitive.content
                    invoked.add(name)
                    """{"value":"result-from-$name"}"""
                }
                else -> error("Unexpected MCP call")
            }
            respond(
                """{"jsonrpc":"2.0","id":${payload["id"]},"result":$result}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }).use { http ->
            val mcp = StreamableHttpMcpClient(HttpTransport(http), "https://mcp.example", setOf("first", "second"))
            val format = parseJsonResponseFormat("answer", """{"type":"object"}""")
            val request = AiRequest(AiModel("test"), emptyList(), responseFormat = format)
            val final = AiStreamEvent.Completed(AiResponse(emptyList()))
            val provider = object : StreamingAiProvider {
                override suspend fun generate(request: AiRequest): AiResponse = error("Unexpected buffered generation")

                override fun stream(request: AiRequest) = flowOf(final).also {
                    assertSame(format, request.responseFormat)
                    assertTrue("result-from-first" in request.input[0].toString())
                    assertTrue("result-from-second" in request.input[1].toString())
                }
            }
            val events = provider.streamWithMcp(request, listOf(McpToolInvocation(mcp, "first"), McpToolInvocation(mcp, "second")))
            assertTrue(invoked.isEmpty())
            assertEquals(listOf(final), events.toList())
            assertEquals(listOf("first", "second"), invoked)
        }
    }

    private fun planner(
        vararg calls: Pair<String, String>,
        onFinal: (AiRequest) -> AiResponse = { error("Unexpected final generation") },
    ): AiProvider = object : AiProvider {
        override suspend fun generate(request: AiRequest): AiResponse {
            val responseFormat = request.responseFormat ?: return onFinal(request)
            assertEquals("mcp_tool_selection", responseFormat.name)
            return AiResponse(
                output = emptyList(),
                structuredOutput = AiValue.Object(mapOf("calls" to AiValue.Array(calls.map { (name, arguments) ->
                    AiValue.Object(mapOf("name" to AiValue.Text(name), "arguments" to AiValue.Text(arguments)))
                }))),
            )
        }
    }
}
