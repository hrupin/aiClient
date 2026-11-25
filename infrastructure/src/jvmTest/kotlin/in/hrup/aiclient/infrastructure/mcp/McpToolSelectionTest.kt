package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.StructuredOutputException
import `in`.hrup.aiclient.model.tool.AiTool
import `in`.hrup.aiclient.model.value.AiValue
import `in`.hrup.aiclient.provider.AiProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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
