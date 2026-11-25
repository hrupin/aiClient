package `in`.hrup.aiclient

import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.generation.AiStreamEvent
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
    fun generatesWithMultipleMcpResults() = verifiesMultipleMcpResults(streaming = false)

    @Test
    fun streamsWithMultipleMcpResults() = verifiesMultipleMcpResults(streaming = true)

    private fun verifiesMultipleMcpResults(streaming: Boolean) = runBlocking {
        var providerCalls = 0
        var finalRequest = ""
        val finalResponse = if (streaming) {
            """data: {"choices":[{"index":0,"delta":{"content":"Done"}}]}

data: {"id":"provider-response","choices":[{"index":0,"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2}}

data: [DONE]

"""
        } else {
            """{"id":"provider-response","choices":[{"finish_reason":"stop","message":{"content":"Done"}}],"usage":{"prompt_tokens":10,"completion_tokens":2}}"""
        }
        val methods = mutableListOf<String>()
        val invoked = mutableListOf<String>()
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
                    "tools/list" -> """{"tools":[{"name":"lookup","inputSchema":{"type":"object"}},{"name":"summarize","inputSchema":{"type":"object"}},{"name":"private_tool","inputSchema":{"type":"object"}}]}"""
                    "tools/call" -> {
                        val params = payload.getValue("params").jsonObject
                        val name = params.getValue("name").jsonPrimitive.content
                        invoked += name
                        val expectedArguments = if (name == "lookup") """{"id":42}""" else "{}"
                        assertEquals(Json.parseToJsonElement(expectedArguments), params.getValue("arguments"))
                        """{"content":[{"type":"text","text":"result-from-$name"}]}"""
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
                    assertTrue("summarize" in payload.toString())
                    assertFalse("private_tool" in payload.toString())
                    val plan = JsonPrimitive(
                        """{"calls":[{"name":"summarize","arguments":"{}"},{"name":"lookup","arguments":"{\"id\":42}"}]}""",
                    )
                    respond("""{"choices":[{"finish_reason":"stop","message":{"content":$plan}}]}""")
                } else {
                    assertEquals(listOf("summarize", "lookup"), invoked)
                    finalRequest = bytes.decodeToString()
                    val firstResult = finalRequest.indexOf("result-from-summarize")
                    assertTrue(firstResult >= 0)
                    assertTrue(finalRequest.indexOf("result-from-lookup") > firstResult)
                    if (streaming) {
                        respond(finalResponse, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
                    } else {
                        respond(finalResponse)
                    }
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
            val mcpTools = mapOf("lookup-server" to setOf("lookup", "summarize"))
            val result = if (streaming) {
                val flow = client.stream("Look up the result", model = "override-model", mcpTools = mcpTools)
                assertEquals(0, providerCalls)
                assertIs<AiStreamEvent.Completed>(flow.toList().last()).response
            } else {
                client.generate("Look up the result", model = "override-model", mcpTools = mcpTools)
            }
            assertEquals(listOf(AiContentPart.Text("Done")), assertIs<AiMessage>(result.output.single()).content)
            assertEquals("Done", result.content)
            assertEquals(finalResponse, result.response)
            assertEquals(finalRequest, result.request)
            assertEquals("kimi", result.provider)
            assertEquals("override-model", result.model)
            assertEquals(10L, result.tokensSent)
            assertEquals(2L, result.tokensReceived)
            assertEquals(2, providerCalls)
            assertEquals(
                listOf(
                    "initialize", "notifications/initialized", "tools/list",
                    "initialize", "notifications/initialized", "tools/call",
                    "initialize", "notifications/initialized", "tools/call",
                ),
                methods,
            )
        }
    }

    @Test
    fun streamCompletionExposesTheSameResponseFields() = runBlocking {
        val jsonResult = """{"value":"готово"}"""
        val responseBody = """data: {"choices":[{"index":0,"delta":{"content":${JsonPrimitive(jsonResult)}}}]}

data: {"id":"provider-response","choices":[{"index":0,"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":1}}

data: [DONE]

"""
        var requestBody = ""
        HttpClient(MockEngine { request ->
            requestBody = assertIs<OutgoingContent.ByteArrayContent>(request.body).bytes().decodeToString()
            respond(responseBody, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }).use { http ->
            val client = AiClient(http, AiClientConfig(
                providers = mapOf(AiProviderId.KIMI to AiProviderConfig("provider-key", "default-model")),
            ))
            val format = parseJsonResponseFormat(
                "result", """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}""",
            )
            val events = client.stream("Finish", provider = AiProviderId.KIMI, responseFormat = format).toList()
            val result = assertIs<AiStreamEvent.Completed>(events.last()).response
            assertEquals(jsonResult, result.content)
            assertEquals(responseBody, result.response)
            assertEquals(requestBody, result.request)
            assertTrue(Json.parseToJsonElement(result.request).jsonObject.getValue("stream").jsonPrimitive.content == "true")
            assertEquals("kimi", result.provider)
            assertEquals("default-model", result.model)
            assertEquals(8L, result.tokensSent)
            assertEquals(1L, result.tokensReceived)
        }
    }

}
