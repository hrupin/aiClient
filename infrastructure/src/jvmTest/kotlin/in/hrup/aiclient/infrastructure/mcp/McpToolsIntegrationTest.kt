package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.infrastructure.http.HttpResponseHead
import `in`.hrup.aiclient.infrastructure.http.HttpStreamPart
import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.http.createHttpClient
import `in`.hrup.aiclient.infrastructure.http.sse.SseDecoder
import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpToolsIntegrationTest {
    private val prettyJson = Json { prettyPrint = true }

    @Test
    fun listsToolsWithInputSchemas() = runBlocking {
        createHttpClient().use { client ->
            val mcp = McpToolsProbe(HttpTransport(client))
            val result = withTimeout(30_000) { mcp.listTools() }
            JsonStructuredOutput(parseJsonResponseFormat("mcp_tools", listSchema)).validate(result.toString())
            println(prettyJson.encodeToString(result))
        }
    }

    private val listSchema = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "required": ["tools"],
          "additionalProperties": false,
          "properties": {
            "tools": {
              "type": "array",
              "items": {
                "type": "object",
                "required": ["name", "inputSchema"],
                "properties": {
                  "name": {"type": "string", "minLength": 1},
                  "description": {"type": "string"},
                  "inputSchema": {"type": "object"}
                }
              }
            }
          }
        }
    """.trimIndent()
}

private class McpToolsProbe(private val transport: HttpTransport) {
    private val endpoint = "https://agpt.hrup.in/mcp"
    private var version: String? = null
    private var session: String? = null
    private var nextId = 0

    suspend fun listTools(): JsonObject {
        val requestedVersion = "2025-11-25"
        val initialized = rpc("initialize", Json.parseToJsonElement("""
            {
              "protocolVersion":"$requestedVersion",
              "capabilities":{},
              "clientInfo":{"name":"aiClient integration test","version":"1.0"}
            }
        """.trimIndent()) as JsonObject)
        // This server omits protocolVersion in its initialize response.
        version = (initialized["protocolVersion"] as? JsonPrimitive)?.content ?: requestedVersion
        assertTrue(version in setOf("2025-11-25", "2025-06-18", "2025-03-26"), "Unsupported MCP revision: $version")
        assertIs<JsonObject>(assertIs<JsonObject>(initialized["capabilities"])["tools"])

        try {
            val notification = transport.execute(endpoint) {
                configurePost("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            }
            assertEquals(202, notification.head.status.value)

            val tools = mutableListOf<JsonObject>()
            val seenCursors = mutableSetOf<String>()
            var cursor: String? = null
            do {
                val params = cursor?.let { JsonObject(mapOf("cursor" to JsonPrimitive(it))) }
                val page = rpc("tools/list", params)
                tools += assertIs<JsonArray>(page["tools"]).map { assertIs<JsonObject>(it) }
                cursor = page["nextCursor"]?.takeUnless { it == JsonNull }?.let {
                    assertIs<JsonPrimitive>(it).content.also { next ->
                        assertTrue(seenCursors.add(next), "MCP server repeated a pagination cursor")
                    }
                }
            } while (cursor != null)
            return JsonObject(mapOf("tools" to JsonArray(tools)))
        } finally {
            if (session != null) {
                val closed = transport.execute(endpoint) {
                    method = HttpMethod.Delete
                    sessionHeaders()
                }
                assertTrue(closed.head.status.value in 200..299 || closed.head.status.value == 405)
            }
        }
    }

    private suspend fun rpc(rpcMethod: String, params: JsonObject?): JsonObject {
        val id = JsonPrimitive(++nextId)
        val fields = mutableMapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to id, "method" to JsonPrimitive(rpcMethod))
        val request = JsonObject(fields + if (params == null) emptyMap() else mapOf("params" to params))
        val decoder = SseDecoder()
        val body = java.io.ByteArrayOutputStream()
        var isSse = false
        val reply = transport.stream(endpoint) { configurePost(request.toString()) }
            .transform { part ->
                when (part) {
                    is HttpStreamPart.Head -> {
                        checkStatus(part.value, rpcMethod)
                        if (rpcMethod == "initialize") session = part.value.headers["Mcp-Session-Id"]
                        isSse = part.value.headers[HttpHeaders.ContentType]
                            ?.substringBefore(';')?.trim().equals("text/event-stream", ignoreCase = true)
                    }
                    is HttpStreamPart.Chunk -> if (isSse) {
                        decoder.decode(part.bytes) { frame ->
                            val message = Json.parseToJsonElement(frame.data) as? JsonObject
                            if (message?.get("id") == id) emit(message)
                        }
                    } else {
                        body.write(part.bytes)
                    }
                }
            }.firstOrNull() ?: run {
                check(!isSse) { "SSE ended before the MCP $rpcMethod response" }
                assertIs<JsonObject>(Json.parseToJsonElement(body.toByteArray().decodeToString()))
            }
        assertEquals(JsonPrimitive("2.0"), reply["jsonrpc"])
        assertEquals(id, reply["id"])
        check(reply["error"] == null) { "MCP $rpcMethod failed: ${reply["error"]}" }
        return assertIs<JsonObject>(reply["result"])
    }

    private fun checkStatus(head: HttpResponseHead, method: String) {
        check(head.status.value == 200) { "MCP $method returned HTTP ${head.status.value}" }
    }

    private fun HttpRequestBuilder.configurePost(body: String) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        accept(ContentType.Application.Json)
        accept(ContentType.Text.EventStream)
        sessionHeaders()
        setBody(body)
    }

    private fun HttpRequestBuilder.sessionHeaders() {
        version?.let { header("MCP-Protocol-Version", it) }
        session?.let { header("Mcp-Session-Id", it) }
    }
}
