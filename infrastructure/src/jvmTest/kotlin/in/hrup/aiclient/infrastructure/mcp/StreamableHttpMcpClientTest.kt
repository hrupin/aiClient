package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.model.value.AiValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamableHttpMcpClientTest {
    @Test
    fun discoversAllowlistedToolsAcrossPages() = runBlocking {
        HttpClient(MockEngine { request ->
            val payload = request.payload()
            val id = payload["id"]
            val result = when (payload.getValue("method").jsonPrimitive.content) {
                "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}}}"""
                "notifications/initialized" -> return@MockEngine respond("", HttpStatusCode.Accepted)
                "tools/list" -> when (id?.jsonPrimitive?.content) {
                    "2" -> """{"tools":[{"name":"hidden","inputSchema":{"type":"object"}}],"nextCursor":"page2"}"""
                    else -> {
                        assertEquals("page2", payload.getValue("params").jsonObject.getValue("cursor").jsonPrimitive.content)
                        """{"tools":[{"name":"allowed","inputSchema":{"type":"object"}}]}"""
                    }
                }
                else -> error("Unexpected request")
            }
            respond(
                """{"jsonrpc":"2.0","id":$id,"result":$result}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }).use { client ->
            val mcp = StreamableHttpMcpClient(HttpTransport(client), "https://mcp.example", setOf("allowed"))
            assertEquals(listOf("allowed"), mcp.listTools().map { it.name })
        }
    }

    @Test
    fun rejectsToolsOutsideTheAllowlistBeforeNetworkIo() = runBlocking {
        HttpClient(MockEngine { error("Unexpected request") }).use { client ->
            val mcp = StreamableHttpMcpClient(HttpTransport(client), "https://mcp.example", setOf("allowed"))
            assertFailsWith<IllegalArgumentException> { mcp.callTool("hidden") }
        }
        Unit
    }

    @Test
    fun toolCallsAreNotRetriedEvenWithHttpRetryInstalled() = runBlocking {
        var calls = 0
        HttpClient(MockEngine { request ->
            val payload = request.payload()
            when (payload.getValue("method").jsonPrimitive.content) {
                "initialize" -> respond(
                    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}}}}""",
                    headers = jsonHeaders,
                )
                "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
                "tools/call" -> {
                    calls++
                    respond("SENSITIVE_RESPONSE", HttpStatusCode.InternalServerError)
                }
                else -> error("Unexpected MCP request")
            }
        }) {
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 2)
                delayMillis { 0 }
            }
        }.use { client ->
            val mcp = StreamableHttpMcpClient(HttpTransport(client), "https://mcp.example", setOf("update"))
            val error = assertFailsWith<McpException> { mcp.callTool("update") }
            assertTrue("500" in error.message.orEmpty())
            assertFalse("SENSITIVE_RESPONSE" in error.stackTraceToString())
            assertEquals(1, calls)
        }
    }

    @Test
    fun oversizedToolBodyIsRejectedInTransport() = runBlocking {
        HttpClient(MockEngine { request ->
            val payload = request.payload()
            when (payload.getValue("method").jsonPrimitive.content) {
                "initialize" -> respond(
                    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}}}}""",
                    headers = jsonHeaders,
                )
                "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
                "tools/call" -> respond(
                    """{"jsonrpc":"2.0","id":2,"result":{"text":"${"x".repeat(1_000)}"}}""",
                    headers = jsonHeaders,
                )
                else -> error("Unexpected MCP request")
            }
        }).use { client ->
            val mcp = StreamableHttpMcpClient(HttpTransport(client), "https://mcp.example", setOf("lookup"))
            val error = assertFailsWith<McpException> {
                mcp.callTool("lookup", AiValue.Object(emptyMap()), maxResultBytes = 128)
            }
            assertTrue("size limit" in error.message.orEmpty())
        }
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun HttpRequestData.payload() = Json.parseToJsonElement(
        assertIs<OutgoingContent.ByteArrayContent>(body).bytes().decodeToString(),
    ).jsonObject
}
