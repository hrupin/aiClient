package `in`.hrup.aiclient.infrastructure.providers

import `in`.hrup.aiclient.infrastructure.catalog.providerModelCatalog
import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.http.createHttpClient
import `in`.hrup.aiclient.infrastructure.mcp.StreamableHttpMcpClient
import `in`.hrup.aiclient.infrastructure.mcp.McpAvailableTool
import `in`.hrup.aiclient.model.tool.AiTool
import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import `in`.hrup.aiclient.infrastructure.providers.deepseek.DeepSeekResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.kimi.KimiChatProvider
import `in`.hrup.aiclient.infrastructure.providers.openai.OpenAiResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.perplexity.PerplexityAgentProvider
import `in`.hrup.aiclient.infrastructure.providers.xai.XaiResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.zai.ZaiChatProvider
import `in`.hrup.aiclient.provider.StreamingAiProvider
import `in`.hrup.aiclient.model.value.AiValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(Parameterized::class)
class ProviderMatrixIntegrationTest(
    private val provider: String,
    private val model: String,
) {
    @Test
    fun bufferedWithoutSchema() = runBlocking { runCase() }

    @Test
    fun bufferedWithSchema() = runBlocking { runCase(structured = true) }

    @Test
    fun streamingWithoutSchema() = runBlocking { runCase(streaming = true) }

    @Test
    fun streamingWithSchema() = runBlocking { runCase(structured = true, streaming = true) }

    @Test
    fun mcpBufferedWithoutSchema() = runBlocking { runCase(mcpEnabled = true) }

    @Test
    fun mcpBufferedWithSchema() = runBlocking { runCase(structured = true, mcpEnabled = true) }

    @Test
    fun mcpStreamingWithoutSchema() = runBlocking { runCase(streaming = true, mcpEnabled = true) }

    @Test
    fun mcpStreamingWithSchema() = runBlocking { runCase(structured = true, streaming = true, mcpEnabled = true) }

    private suspend fun runCase(
        structured: Boolean = false,
        streaming: Boolean = false,
        mcpEnabled: Boolean = false,
    ) {
        assumeTrue("BFL catalog entries are image generation endpoints", provider != "BFL")
        if (!mcpEnabled) {
            generateLive(
                provider, model, if (structured) arithmeticFormat() else null, streaming,
                exactModel = true, createProvider = ::createProvider,
            )
            return
        }
        val endpoint = System.getProperty("aiMcpUrl")
        val fixture = endpoint == null

        val tool = "get_available_models"
        val format = if (structured) parseJsonResponseFormat(
            "model_sample",
            """{"type":"object","properties":{"id":{"type":"string","minLength":1},"provider":{"type":"string","minLength":1}},"required":["id","provider"],"additionalProperties":false}""",
        ) else null
        val instruction = "Use an available MCP tool to list models. Select the first model from the tool result and return its exact ID and provider" +
            if (structured) " as a JSON object with exactly two string properties, \"id\" and \"provider\". The root value must be an object, never an array." else " in one line."
        val client = if (fixture) mcpFixture() else createHttpClient()
        client.use {
            val mcp = StreamableHttpMcpClient(HttpTransport(client), endpoint ?: "https://mcp.example", setOf(tool, "get_current_time"))
            generateLive(
                provider, model, format, streaming,
                exactModel = true,
                promptOverride = instruction,
                mcpTools = if (fixture) {
                    val emptyInput = parseJsonResponseFormat(
                        "empty_input",
                        """{"type":"object","properties":{},"additionalProperties":false}""",
                    ).schema
                    listOf(
                        McpAvailableTool(mcp, AiTool("get_current_time", "Returns the current time", emptyInput)),
                        McpAvailableTool(mcp, AiTool(tool, "Lists available AI models and their providers", emptyInput)),
                    )
                } else {
                    mcp.listTools().filter { it.name == tool }.map { McpAvailableTool(mcp, it) }
                        .also { check(it.isNotEmpty()) { "MCP server did not list $tool" } }
                },
                expectedStructured = if (fixture) AiValue.Object(
                    mapOf("id" to AiValue.Text("fixture-model"), "provider" to AiValue.Text("fixture-provider")),
                ) else null,
                expectedTextFragments = if (fixture) listOf("fixture-model", "fixture-provider") else emptyList(),
                createProvider = ::createProvider,
            )
        }
    }

    private fun mcpFixture(): HttpClient {
        var step = 0
        return HttpClient(MockEngine { request ->
            val current = ++step
            if (current > 1) {
                assertEquals("matrix-session", request.headers["Mcp-Session-Id"])
                assertEquals("2025-11-25", request.headers["MCP-Protocol-Version"])
            }
            if (current == 4) {
                assertEquals(HttpMethod.Delete, request.method)
                return@MockEngine respond("", HttpStatusCode.NoContent)
            }
            assertEquals(HttpMethod.Post, request.method)
            val body = assertIs<OutgoingContent.ByteArrayContent>(request.body).bytes().decodeToString()
            val payload = Json.parseToJsonElement(body).jsonObject
            val method = payload.getValue("method").jsonPrimitive.content
            assertEquals(listOf("initialize", "notifications/initialized", "tools/call")[current - 1], method)
            val id = payload["id"]
            when (current) {
                1 -> respond(
                    """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"matrix","version":"1"}}}""",
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        "Mcp-Session-Id" to listOf("matrix-session"),
                    ),
                )
                2 -> respond("", HttpStatusCode.Accepted)
                else -> {
                    assertEquals("get_available_models", payload.getValue("params").jsonObject.getValue("name").jsonPrimitive.content)
                    respond(
                        """data: {"jsonrpc":"2.0","id":$id,"result":{"content":[],"structuredContent":{"models":[{"id":"fixture-model","provider":"fixture-provider"}]}}}""" + "\n\n",
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                }
            }
        })
    }

    private fun createProvider(transport: HttpTransport, key: String): StreamingAiProvider = when (provider) {
        "OPENAI" -> OpenAiResponsesProvider(transport, key)
        "DEEPSEEK" -> DeepSeekResponsesProvider(transport, key)
        "XAI" -> XaiResponsesProvider(transport, key)
        "ZAI" -> ZaiChatProvider(transport, key)
        "KIMI" -> KimiChatProvider(transport, key)
        "PERPLEXITY" -> PerplexityAgentProvider(transport, key)
        else -> error("Unknown provider: $provider")
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}")
        fun providers(): List<Array<String>> = providerModelCatalog.flatMap { (provider, models) ->
            models.map { model -> arrayOf(provider.uppercase(), model.id) }
        }
    }
}
