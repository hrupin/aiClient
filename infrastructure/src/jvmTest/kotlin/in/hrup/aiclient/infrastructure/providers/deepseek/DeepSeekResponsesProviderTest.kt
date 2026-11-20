package `in`.hrup.aiclient.infrastructure.providers.deepseek

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiUsage
import `in`.hrup.aiclient.model.generation.StructuredOutputException
import `in`.hrup.aiclient.model.value.AiValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DeepSeekResponsesProviderTest {
    private val message = AiMessage(AiRole.USER, listOf(AiContentPart.Text("Return the result")))
    private val schema = """{"type":"object","properties":{"value":{"type":"integer"}},"required":["value"],"additionalProperties":false}"""
    private val format = parseJsonResponseFormat("result", schema)

    @Test
    fun flashSendsTextRequestAndMapsResponse() = runBlocking {
        HttpClient(MockEngine { call ->
            assertEquals("https://api.deepseek.com/responses", call.url.toString())
            assertEquals(HttpMethod.Post, call.method)
            assertEquals("Bearer test-key", call.headers[HttpHeaders.Authorization])
            val body = assertIs<OutgoingContent.ByteArrayContent>(call.body)
            assertEquals(
                Json.parseToJsonElement("""{"model":"deepseek-flash","input":[{"role":"user","content":"Return the result"}]}"""),
                Json.parseToJsonElement(body.bytes().decodeToString()),
            )
            respond(completed("Done"))
        }).use { client ->
            val provider = DeepSeekResponsesProvider(HttpTransport(client), "test-key")
            val response = provider.generate(AiRequest(AiModel("deepseek-flash"), listOf(message)))
            assertEquals(AiMessage(AiRole.ASSISTANT, listOf(AiContentPart.Text("Done"))), response.output.single())
            assertEquals(AiUsage(3, 5, 8), response.usage)
        }
    }

    @Test
    fun proSendsDeepSeekSchemaAndValidatesResponse() = runBlocking {
        HttpClient(MockEngine { call ->
            val body = assertIs<OutgoingContent.ByteArrayContent>(call.body)
            val payload = Json.parseToJsonElement(body.bytes().decodeToString())
            assertEquals(
                Json.parseToJsonElement(
                    """{"model":"deepseek-v4-pro","input":[{"role":"user","content":"Return the result"}],"text":{"format":{"type":"json_schema","name":"result","schema":$schema}}}""",
                ),
                payload,
            )
            respond(completed("""{"value":42}"""))
        }).use { client ->
            val provider = DeepSeekResponsesProvider(HttpTransport(client), "test-key")
            val request = AiRequest(AiModel("deepseek-v4-pro"), listOf(message), responseFormat = format)
            assertEquals(AiValue.Object(mapOf("value" to AiValue.Number("42"))), provider.generate(request).structuredOutput)
        }
    }

    @Test
    fun rejectsOutputThatDoesNotMatchSchema() = runBlocking {
        HttpClient(MockEngine { respond(completed("""{"value":"42"}""")) }).use { client ->
            val provider = DeepSeekResponsesProvider(HttpTransport(client), "test-key")
            val request = AiRequest(AiModel("deepseek-flash"), listOf(message), responseFormat = format)
            assertFailsWith<StructuredOutputException> { provider.generate(request) }
        }
        Unit
    }

    @Test
    fun preservesProviderHttpError() = runBlocking {
        HttpClient(MockEngine {
            respond(
                """{"error":{"message":"Too many requests","code":"rate_limit"}}""",
                HttpStatusCode.TooManyRequests,
                headersOf("x-ds-request-id", "ds-test"),
            )
        }).use { client ->
            val provider = DeepSeekResponsesProvider(HttpTransport(client), "test-key")
            val error = assertFailsWith<DeepSeekResponsesException> {
                provider.generate(AiRequest(AiModel("deepseek-flash"), listOf(message)))
            }
            assertEquals(429, error.httpStatus)
            assertEquals("rate_limit", error.code)
            assertEquals("ds-test", error.requestId)
        }
    }

    private fun completed(text: String) = """
        {"status":"completed","output":[{"type":"reasoning"},{"type":"message","content":[
            {"type":"output_text","text":${JsonPrimitive(text)}}
        ]}],"usage":{"input_tokens":3,"output_tokens":5,"total_tokens":8}}
    """.trimIndent()
}
