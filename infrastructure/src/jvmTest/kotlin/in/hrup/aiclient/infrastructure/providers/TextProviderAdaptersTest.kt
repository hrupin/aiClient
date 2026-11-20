package `in`.hrup.aiclient.infrastructure.providers

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.providers.kimi.KimiChatProvider
import `in`.hrup.aiclient.infrastructure.providers.perplexity.PerplexityAgentProvider
import `in`.hrup.aiclient.infrastructure.providers.text.ProviderResponseException
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
import `in`.hrup.aiclient.model.generation.StructuredOutputException
import `in`.hrup.aiclient.model.value.AiValue
import `in`.hrup.aiclient.provider.AiProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TextProviderAdaptersTest {
    private val schema =
        """{"type":"object","properties":{"value":{"type":"integer"}},"required":["value"],"additionalProperties":false}"""
    private val format = parseJsonResponseFormat("result", schema)
    private val messages = """[{"role":"system","content":"Be brief"},{"role":"user","content":"Answer"}]"""
    private val expectedOutput = AiValue.Object(mapOf("value" to AiValue.Number("42")))

    @Test
    fun xaiUsesResponsesTextFormat() = runBlocking {
        HttpClient(MockEngine { call ->
            assertRequest(
                call,
                "https://api.x.ai/v1/responses",
                """{"model":"grok-4.7","input":$messages,"text":{"format":{"type":"json_schema","name":"result","schema":$schema,"strict":true}}}""",
            )
            respond(responsesOutput("""{"value":42}""", "reasoning"))
        }).use { client ->
            val provider = XaiResponsesProvider(HttpTransport(client), "test-key")
            val result = provider.generate(request("grok-4.7").copy(responseFormat = format))
            assertEquals(expectedOutput, result.structuredOutput)
            assertEquals(AiUsage(5, 3, 8), result.usage)
        }
    }

    @Test
    fun perplexityUsesAgentResponseFormatAndSkipsSearchMetadata() = runBlocking {
        HttpClient(MockEngine { call ->
            assertRequest(
                call,
                "https://api.perplexity.ai/v1/agent",
                """{"model":"perplexity/sonar","input":$messages,"response_format":{"type":"json_schema","json_schema":{"name":"result","schema":$schema}}}""",
            )
            respond(responsesOutput("""{"value":42}""", "search_results"))
        }).use { client ->
            val provider = PerplexityAgentProvider(HttpTransport(client), "test-key")
            val result = provider.generate(request("perplexity/sonar").copy(responseFormat = format))
            assertEquals(expectedOutput, result.structuredOutput)
        }
    }

    @Test
    fun zaiSendsChatCompletionsAndMapsText() = runBlocking {
        assertChat(
            "https://api.z.ai/api/paas/v4/chat/completions",
            "glm-5.3",
            { ZaiChatProvider(it, "test-key") },
        )
    }

    @Test
    fun kimiSendsChatCompletionsAndMapsText() = runBlocking {
        assertChat(
            "https://api.moonshot.ai/v1/chat/completions",
            "kimi-k3.1-pro",
            { KimiChatProvider(it, "test-key") },
        )
    }

    @Test
    fun zaiAndKimiSendJsonModeAndValidateTheOriginalSchema() = runBlocking {
        val providers: Map<String, (HttpTransport) -> AiProvider> = mapOf(
            "glm-5.3" to { ZaiChatProvider(it, "test-key") },
            "kimi-k3.1-pro" to { KimiChatProvider(it, "test-key") },
        )
        for ((model, createProvider) in providers) {
            val structuredRequest = request(model).copy(responseFormat = format)
            HttpClient(MockEngine { call ->
                val body = assertIs<OutgoingContent.ByteArrayContent>(call.body)
                val payload = Json.parseToJsonElement(body.bytes().decodeToString()).jsonObject
                assertEquals(Json.parseToJsonElement("""{"type":"json_object"}"""), payload["response_format"])
                val actualMessages = payload.getValue("messages").jsonArray
                assertEquals(Json.parseToJsonElement(messages).jsonArray, actualMessages.drop(1))
                val instruction = actualMessages.first().jsonObject
                assertEquals("system", instruction.getValue("role").jsonPrimitive.content)
                assertTrue(schema in instruction.getValue("content").jsonPrimitive.content)
                respond(chatOutput("""{"value":42}"""))
            }).use { client ->
                assertEquals(expectedOutput, createProvider(HttpTransport(client)).generate(structuredRequest).structuredOutput)
            }
            HttpClient(MockEngine { respond(chatOutput("""{"value":"42"}""")) }).use { client ->
                assertFailsWith<StructuredOutputException> {
                    createProvider(HttpTransport(client)).generate(structuredRequest)
                }
            }
        }
    }

    @Test
    fun rejectsInvalidStructuredOutput() = runBlocking {
        HttpClient(MockEngine { respond(responsesOutput("""{"value":"42"}""", "reasoning")) }).use { client ->
            assertFailsWith<StructuredOutputException> {
                XaiResponsesProvider(HttpTransport(client), "test-key")
                    .generate(request("grok-4.6").copy(responseFormat = format))
            }
        }
        Unit
    }

    @Test
    fun streamingRejectsFinalJsonWithWrongFieldType() = runBlocking {
        val invalid = """{"value":"42"}"""
        val responsesStream = listOf(
            """{"type":"response.completed","response":${responsesOutput(invalid, "reasoning")}}""",
        )
        val chatStream = listOf(
            """{"choices":[{"index":0,"delta":{"content":${JsonPrimitive(invalid)}}}]}""",
            """{"choices":[{"index":0,"finish_reason":"stop"}]}""",
            "[DONE]",
        )
        for ((frames, providerFactory) in listOf(
            responsesStream to { transport: HttpTransport -> XaiResponsesProvider(transport, "test-key") },
            chatStream to { transport: HttpTransport -> KimiChatProvider(transport, "test-key") },
        )) {
            HttpClient(MockEngine {
                respond(
                    frames.joinToString("") { frame ->
                        frame.lineSequence().joinToString("\n") { "data: $it" } + "\n\n"
                    },
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            }).use { client ->
                val provider = providerFactory(HttpTransport(client))
                val error = assertFailsWith<StructuredOutputException> {
                    provider.stream(request("test-model").copy(responseFormat = format)).toList()
                }
                assertTrue(error.violations.isNotEmpty())
            }
        }
    }

    @Test
    fun kimiK3ReturnsTheValidatedObjectFromASingletonArray() = runBlocking {
        val request = request("kimi-k3").copy(responseFormat = format)
        val expectedText = """{"value":42}"""
        HttpClient(MockEngine { call ->
            assertRequest(
                call,
                "https://api.moonshot.ai/v1/chat/completions",
                """{"model":"kimi-k3","messages":$messages,"response_format":{"type":"json_schema","json_schema":{"name":"result","schema":$schema,"strict":true}}}""",
            )
            respond(chatOutput("[$expectedText]"))
        }).use { client ->
            val response = KimiChatProvider(HttpTransport(client), "test-key").generate(request)
            assertEquals(expectedOutput, response.structuredOutput)
            assertEquals(listOf(AiContentPart.Text(expectedText)), assertIs<AiMessage>(response.output.single()).content)
        }

        val frames = listOf(" ", "[", expectedText, "]").map { delta ->
            """{"choices":[{"index":0,"delta":{"content":${JsonPrimitive(delta)}}}]}"""
        } + listOf("""{"choices":[{"index":0,"finish_reason":"stop"}]}""", "[DONE]")
        HttpClient(MockEngine {
            respond(
                frames.joinToString("") { "data: $it\n\n" },
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }).use { client ->
            val events = KimiChatProvider(HttpTransport(client), "test-key").stream(request).toList()
            assertEquals(expectedText, events.filterIsInstance<AiStreamEvent.TextDelta>().joinToString("") { it.text })
            val response = assertIs<AiStreamEvent.Completed>(events.last()).response
            assertEquals(expectedOutput, response.structuredOutput)
            assertEquals(listOf(AiContentPart.Text(expectedText)), assertIs<AiMessage>(response.output.single()).content)
        }
    }

    @Test
    fun kimiK3StillRejectsInvalidObjectsAndAmbiguousArrays() = runBlocking {
        val request = request("kimi-k3").copy(responseFormat = format)
        for (body in listOf("""[{"value":"42"}]""", """[{"value":42},{"value":43}]""")) {
            HttpClient(MockEngine { respond(chatOutput(body)) }).use { client ->
                assertFailsWith<StructuredOutputException> {
                    KimiChatProvider(HttpTransport(client), "test-key").generate(request)
                }
            }
        }
    }

    @Test
    fun preservesApiErrorDetails() = runBlocking {
        HttpClient(MockEngine {
            respond(
                """{"error":"Rate limit","code":"rate_limit"}""",
                HttpStatusCode.TooManyRequests,
                headersOf("x-request-id", "request-123"),
            )
        }).use { client ->
            val provider = XaiResponsesProvider(HttpTransport(client), "test-key")
            val error = assertFailsWith<ProviderResponseException> { provider.generate(request("grok-4.7")) }
            assertEquals(429, error.httpStatus)
            assertEquals("request-123", error.requestId)
            assertEquals("rate_limit", error.code)
            assertEquals("Rate limit", error.message)
        }
    }

    private suspend fun assertChat(
        endpoint: String,
        model: String,
        provider: (HttpTransport) -> AiProvider,
    ) {
        HttpClient(MockEngine { call ->
            assertRequest(call, endpoint, """{"model":"$model","messages":$messages}""")
            respond("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Done","reasoning_content":"hidden"}}],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}""")
        }).use { client ->
            val result = provider(HttpTransport(client)).generate(request(model))
            assertEquals(AiMessage(AiRole.ASSISTANT, listOf(AiContentPart.Text("Done"))), result.output.single())
            assertEquals(AiUsage(5, 3, 8), result.usage)
        }
    }

    private fun assertRequest(call: HttpRequestData, endpoint: String, expectedBody: String) {
        assertEquals(endpoint, call.url.toString())
        assertEquals(HttpMethod.Post, call.method)
        assertEquals("Bearer test-key", call.headers[HttpHeaders.Authorization])
        val body = assertIs<OutgoingContent.ByteArrayContent>(call.body)
        assertEquals(Json.parseToJsonElement(expectedBody), Json.parseToJsonElement(body.bytes().decodeToString()))
    }

    private fun responsesOutput(text: String, metadata: String) = """
        {"status":"completed","output":[{"type":"$metadata"},{"type":"message","content":[
            {"type":"output_text","text":${JsonPrimitive(text)}}
        ]}],"usage":{"input_tokens":5,"output_tokens":3,"total_tokens":8}}
    """.trimIndent()

    private fun chatOutput(text: String) =
        """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":${JsonPrimitive(text)}}}]}"""

    private fun request(model: String) = AiRequest(
        model = AiModel(model),
        input = listOf(
            AiMessage(AiRole.SYSTEM, listOf(AiContentPart.Text("Be brief"))),
            AiMessage(AiRole.USER, listOf(AiContentPart.Text("Answer"))),
        ),
    )
}
