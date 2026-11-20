package `in`.hrup.aiclient

import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AiClientTest {
    @Test
    fun missingAndBlankKeysFailAtCallTimeBeforeAnyHttp() = runBlocking {
        HttpClient(MockEngine { error("Unexpected HTTP request") }).use { http ->
            val client = AiClient(http, AiClientConfig(
                defaultProvider = AiProviderId.KIMI,
                providers = mapOf(AiProviderId.XAI to AiProviderConfig(apiKey = " ")),
            ))
            val missing = assertFailsWith<MissingAiApiKeyException> { client.generate("Hello") }
            assertEquals(AiProviderId.KIMI, missing.provider)
            assertTrue("KIMI_API_KEY" in missing.message.orEmpty())

            val stream = client.stream("Hello", provider = AiProviderId.XAI)
            val blank = assertFailsWith<MissingAiApiKeyException> { stream.toList() }
            assertEquals(AiProviderId.XAI, blank.provider)
        }
    }

    @Test
    fun routesThroughConfiguredProviderModelAndMcpServer() = runBlocking {
        var providerCalls = 0
        val methods = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            val bytes = assertIs<OutgoingContent.ByteArrayContent>(request.body).bytes()
            val payload = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            if (request.url.host == "mcp.example") {
                assertEquals("Bearer mcp-auth", request.headers[HttpHeaders.Authorization])
                val method = payload.getValue("method").jsonPrimitive.content
                methods += method
                val result = when (method) {
                    "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}}}"""
                    "notifications/initialized" -> return@MockEngine respond("", HttpStatusCode.Accepted)
                    "tools/list" -> """{"tools":[{"name":"lookup","inputSchema":{"type":"object"}},{"name":"private_tool","inputSchema":{"type":"object"}}]}"""
                    "tools/call" -> {
                        assertEquals("lookup", payload.getValue("params").jsonObject.getValue("name").jsonPrimitive.content)
                        """{"content":[{"type":"text","text":"from-mcp"}]}"""
                    }
                    else -> error("Unexpected MCP method")
                }
                respond(
                    """{"jsonrpc":"2.0","id":${payload["id"]},"result":$result}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                assertEquals("ai.example", request.url.host)
                assertEquals("Bearer provider-key", request.headers[HttpHeaders.Authorization])
                assertEquals("override-model", payload.getValue("model").jsonPrimitive.content)
                if (++providerCalls == 1) {
                    assertTrue("lookup" in payload.toString())
                    assertFalse("private_tool" in payload.toString())
                    val plan = JsonPrimitive("""{"name":"lookup","arguments":"{}"}""")
                    respond("""{"choices":[{"finish_reason":"stop","message":{"content":$plan}}]}""")
                } else {
                    assertTrue("from-mcp" in payload.toString())
                    respond("""{"choices":[{"finish_reason":"stop","message":{"content":"Done"}}]}""")
                }
            }
        }).use { http ->
            val client = AiClient(http, AiClientConfig(
                defaultProvider = AiProviderId.KIMI,
                providers = mapOf(AiProviderId.KIMI to AiProviderConfig("provider-key", "default-model", "https://ai.example")),
                mcpServers = mapOf("lookup-server" to McpServerConfig(
                    "https://mcp.example", mapOf("Authorization" to "Bearer mcp-auth"),
                )),
            ))
            val result = client.generate("Look up the result", model = "override-model", mcpTools = mapOf("lookup-server" to setOf("lookup")))
            assertEquals(listOf(AiContentPart.Text("Done")), assertIs<AiMessage>(result.output.single()).content)
            assertEquals(2, providerCalls)
            assertEquals(
                listOf("initialize", "notifications/initialized", "tools/list", "initialize", "notifications/initialized", "tools/call"),
                methods,
            )
        }
    }
}
