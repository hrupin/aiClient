package `in`.hrup.aiclient.infrastructure.providers.openai

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiImageSource
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.model.generation.AiUsage
import `in`.hrup.aiclient.provider.AiProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiResponsesProviderTest {
    private val request = AiRequest(
        AiModel("test-model"),
        listOf(
            AiMessage(AiRole.DEVELOPER, listOf(AiContentPart.Text("Be brief"))),
            AiMessage(AiRole.USER, listOf(AiContentPart.Text("Привет"))),
        ),
    )

    @Test
    fun sendsTextAndMapsOrderedOutputAndUsage() = runBlocking {
        val endpoint = "https://example.test/v1/responses"
        HttpClient(MockEngine { call ->
            assertEquals(endpoint, call.url.toString())
            assertEquals(HttpMethod.Post, call.method)
            assertEquals("Bearer test-key", call.headers[HttpHeaders.Authorization])
            val content = assertIs<OutgoingContent.ByteArrayContent>(call.body)
            assertEquals("application/json", content.contentType?.withoutParameters().toString())
            assertEquals(
                Json.parseToJsonElement("""{"model":"test-model","input":[{"role":"developer","content":"Be brief"},{"role":"user","content":"Привет"}]}"""),
                Json.parseToJsonElement(content.bytes().decodeToString()),
            )
            respond(
                """{
                    "status":"completed",
                    "output":[
                      {"type":"reasoning","summary":[]},
                      {"type":"message","role":"assistant","content":[
                        {"type":"output_text","text":"Привет!","annotations":[]},
                        {"type":"output_text","text":"Чем помочь?"}
                      ]}
                    ],
                    "usage":{"input_tokens":5,"output_tokens":7,"total_tokens":12}
                }""",
            )
        }).use { client ->
            val provider: AiProvider = OpenAiResponsesProvider(HttpTransport(client), "test-key", endpoint)
            val response = provider.generate(request)
            assertEquals(
                AiMessage(AiRole.ASSISTANT, listOf(AiContentPart.Text("Привет!"), AiContentPart.Text("Чем помочь?"))),
                response.output.single(),
            )
            assertEquals(AiUsage(5, 7, 12), response.usage)
        }
    }

    @Test
    fun preservesHttpErrorDetails() = runBlocking {
        HttpClient(MockEngine {
            respond(
                """{"error":{"message":"Invalid API key","code":"invalid_api_key"}}""",
                HttpStatusCode.Unauthorized,
                headersOf("x-request-id", "req-test"),
            )
        }).use { client ->
            val provider = OpenAiResponsesProvider(HttpTransport(client), "test-key")
            val error = assertFailsWith<OpenAiResponsesException> { provider.generate(request) }
            assertEquals(401, error.httpStatus)
            assertEquals("req-test", error.requestId)
            assertEquals("invalid_api_key", error.code)
            assertEquals("Invalid API key", error.message)
        }
    }

    @Test
    fun sendsTextAndImagesInOrder() = runBlocking {
        HttpClient(MockEngine { call ->
            val body = assertIs<OutgoingContent.ByteArrayContent>(call.body).bytes().decodeToString()
            val expected = """{"model":"test-model","input":[{"role":"user","content":[
                {"type":"input_text","text":"Compare"},
                {"type":"input_image","image_url":"https://example.test/image.png"},
                {"type":"input_image","image_url":"data:image/png;base64,AA=="}
            ]}]}"""
            assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(body))
            respond("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Done"}]}]}""")
        }).use { client ->
            val provider = OpenAiResponsesProvider(HttpTransport(client), "test-key")
            val input = request.copy(input = listOf(AiMessage(AiRole.USER, listOf(
                AiContentPart.Text("Compare"),
                AiContentPart.Image(AiImageSource.Url("https://example.test/image.png")),
                AiContentPart.Image(AiImageSource.Base64("AA==", "image/png")),
            ))))
            assertEquals("Done", provider.generate(input).content)
        }
    }

    @Test
    fun emitsDeltasWithDomainIndicesAndCompletesBeforeHttpEof() = runBlocking {
        val message = """{"type":"message","content":[{"type":"output_text","text":"Привет!"}]}"""
        val response = ByteChannel(autoFlush = true)
        response.writeStringUtf8(
            sse(
                """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}""",
                """{"type":"response.output_item.added","output_index":1,"item":{"type":"message","content":[]}}""",
                """{"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"Привет"}""",
                """{"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"!"}""",
                """{"type":"response.output_item.done","output_index":1,"item":$message}""",
                """{"type":"response.completed","response":{"status":"completed","output":[{"type":"reasoning"},$message],"usage":{"total_tokens":12}}}""",
            ),
        )
        HttpClient(MockEngine { call ->
            assertEquals(HttpMethod.Post, call.method)
            assertEquals("text/event-stream", call.headers[HttpHeaders.Accept])
            val content = assertIs<OutgoingContent.ByteArrayContent>(call.body)
            assertTrue(Json.decodeFromString<ResponsesRequest>(content.bytes().decodeToString()).stream)
            respond(response, headers = eventStreamHeaders)
        }).use { client ->
            val provider = OpenAiResponsesProvider(HttpTransport(client), "test-key")
            val output = AiMessage(AiRole.ASSISTANT, listOf(AiContentPart.Text("Привет!")))
            assertEquals(
                listOf(
                    AiStreamEvent.TextDelta(0, 0, "Привет"),
                    AiStreamEvent.TextDelta(0, 0, "!"),
                    AiStreamEvent.OutputItemCompleted(0, output),
                    AiStreamEvent.Completed(AiResponse(listOf(output), AiUsage(totalTokens = 12))),
                ),
                withTimeout(5_000) { provider.stream(request).toList() },
            )
            assertTrue(response.isClosedForWrite)
        }
    }

    @Test
    fun earlyCollectorTerminationClosesOpenResponse() = runBlocking {
        val response = ByteChannel(autoFlush = true)
        response.writeStringUtf8(sse(textDelta))
        HttpClient(MockEngine { respond(response, headers = eventStreamHeaders) }).use { client ->
            val provider = OpenAiResponsesProvider(HttpTransport(client), "test-key")
            val first = withTimeout(5_000) { provider.stream(request).first() }
            assertEquals(AiStreamEvent.TextDelta(0, 0, "partial"), first)
            assertTrue(response.isClosedForWrite)
        }
    }

    @Test
    fun eofWithoutCompletedEventFails() = runBlocking {
        HttpClient(MockEngine { respond(sse(textDelta), headers = eventStreamHeaders) }).use { client ->
            val provider = OpenAiResponsesProvider(HttpTransport(client), "test-key")
            val error = assertFailsWith<OpenAiResponsesException> { provider.stream(request).toList() }
            assertEquals("OpenAI stream ended before response.completed", error.message)
        }
    }

    private val eventStreamHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")
    private val textDelta =
        """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"partial"}"""

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }
}
