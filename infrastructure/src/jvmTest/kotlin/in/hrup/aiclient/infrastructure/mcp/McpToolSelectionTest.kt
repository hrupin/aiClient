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

class McpToolSelectionTest {
    @Test
    fun rejectsUnknownToolsAndInvalidArgumentsBeforeMcpRequest() = runBlocking {
        HttpClient(MockEngine { error("Unexpected MCP request") }).use { http ->
            val client = StreamableHttpMcpClient(HttpTransport(http), "https://mcp.example", setOf("lookup"))
            val schema = parseJsonResponseFormat(
                "lookup",
                """{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}""",
            ).schema
            val tools = listOf(McpAvailableTool(client, AiTool("lookup", "Look up an ID", schema)))
            val request = AiRequest(AiModel("test"), emptyList())
            assertFailsWith<McpException> { planner("unknown", "{}").generateWithMcpTools(request, tools) }
            assertFailsWith<StructuredOutputException> {
                planner("lookup", """{"id":"invalid"}""").generateWithMcpTools(request, tools)
            }
        }
        Unit
    }

    private fun planner(name: String, arguments: String): AiProvider = object : AiProvider {
        override suspend fun generate(request: AiRequest): AiResponse {
            assertEquals("mcp_tool_selection", request.responseFormat?.name)
            return AiResponse(
                output = emptyList(),
                structuredOutput = AiValue.Object(
                    mapOf("name" to AiValue.Text(name), "arguments" to AiValue.Text(arguments)),
                ),
            )
        }
    }
}
