package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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

    private fun HttpRequestData.payload() = Json.parseToJsonElement(
        assertIs<OutgoingContent.ByteArrayContent>(body).bytes().decodeToString(),
    ).jsonObject
}
