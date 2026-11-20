package `in`.hrup.aiclient.infrastructure.providers

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.providers.deepseek.DeepSeekResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.kimi.KimiChatProvider
import `in`.hrup.aiclient.infrastructure.providers.perplexity.PerplexityAgentProvider
import `in`.hrup.aiclient.infrastructure.providers.text.ProviderResponseException
import `in`.hrup.aiclient.infrastructure.providers.xai.XaiResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.zai.ZaiChatProvider
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.model.generation.AiUsage
import `in`.hrup.aiclient.provider.StreamingAiProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProviderStreamingTest {
    private val responsesDelta = """{"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"Привет"}"""
    private val chatDelta = """{"choices":[{"index":0,"delta":{"content":"Привет"},"finish_reason":null}]}"""
    private val headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
    private val assistant = AiMessage(AiRole.ASSISTANT, listOf(AiContentPart.Text("Привет!")))

    @Test
    fun allFiveProvidersEmitDeltasAndFinishWithUsage() = runBlocking {
        for (case in cases()) {
            val channel = ByteChannel(autoFlush = true)
            channel.writeStringUtf8(case.events)
            HttpClient(MockEngine { call ->
                assertEquals("text/event-stream", call.headers[HttpHeaders.Accept])
                val payload = assertIs<OutgoingContent.ByteArrayContent>(call.body).bytes().decodeToString()
                val json = Json.parseToJsonElement(payload).jsonObject
                assertEquals(JsonPrimitive(true), json["stream"])
                assertEquals(JsonPrimitive(case.model), json["model"])
                respond(channel, headers = headers)
            }).use { client ->
                val events = withTimeout(5_000) {
                    case.create(HttpTransport(client)).stream(request(case.model)).toList()
                }
                assertEquals(
                    listOf(
                        AiStreamEvent.TextDelta(0, 0, "Привет"),
                        AiStreamEvent.TextDelta(0, 0, "!"),
                        AiStreamEvent.OutputItemCompleted(0, assistant),
                        AiStreamEvent.Completed(AiResponse(listOf(assistant), AiUsage(5, 3, 8))),
                    ),
                    events,
                    case.model,
                )
                assertTrue(channel.isClosedForWrite, case.model)
            }
        }
    }

    @Test
    fun stoppingCollectionClosesTheOpenResponseForEveryProvider() = runBlocking {
        for (case in cases()) {
            val channel = ByteChannel(autoFlush = true)
            channel.writeStringUtf8(sse(case.firstDelta))
            HttpClient(MockEngine { respond(channel, headers = headers) }).use { client ->
                val first = withTimeout(5_000) {
                    case.create(HttpTransport(client)).stream(request(case.model)).first()
                }
                assertEquals(AiStreamEvent.TextDelta(0, 0, "Привет"), first, case.model)
                assertTrue(channel.isClosedForWrite, case.model)
            }
        }
    }

    @Test
    fun eofWithoutTerminalEventFailsForBothProtocols() = runBlocking {
        val cases = cases().filter { it.model == "grok-4.7" || it.model == "glm-5.3" }
        for (case in cases) {
            HttpClient(MockEngine { respond(sse(case.firstDelta), headers = headers) }).use { client ->
                assertFailsWith<ProviderResponseException> {
                    case.create(HttpTransport(client)).stream(request(case.model)).toList()
                }
            }
        }
    }

    @Test
    fun httpErrorKeepsStatusAndRequestId() = runBlocking {
        HttpClient(MockEngine {
            respond("denied", HttpStatusCode.Unauthorized, headersOf("x-request-id", "test-id"))
        }).use { client ->
            val error = assertFailsWith<ProviderResponseException> {
                XaiResponsesProvider(HttpTransport(client), "test-key").stream(request("grok-4.7")).toList()
            }
            assertEquals(401, error.httpStatus)
            assertEquals("test-id", error.requestId)
        }
    }

    private fun cases(): List<StreamCase> {
        val responses = responsesEvents("reasoning")
        val chat = sse(
            chatDelta,
            """{"choices":[{"index":0,"delta":{"content":"!"},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}""",
            "[DONE]",
        )
        return listOf(
            StreamCase("deepseek-flash", responsesDelta, responses) { DeepSeekResponsesProvider(it, "test-key") },
            StreamCase("grok-4.7", responsesDelta, responses) { XaiResponsesProvider(it, "test-key") },
            StreamCase("perplexity/sonar", responsesDelta, responsesEvents("search_results")) {
                PerplexityAgentProvider(it, "test-key")
            },
            StreamCase("glm-5.3", chatDelta, chat) { ZaiChatProvider(it, "test-key") },
            StreamCase("kimi-k3.1-pro", chatDelta, chat) { KimiChatProvider(it, "test-key") },
        )
    }

    private fun responsesEvents(metadata: String): String {
        val message = """{"type":"message","content":[{"type":"output_text","text":"Привет!"}]}"""
        return sse(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"$metadata"}}""",
            """{"type":"response.output_item.added","output_index":1,"item":{"type":"message"}}""",
            responsesDelta,
            """{"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"!"}""",
            """{"type":"response.output_item.done","output_index":1,"item":$message}""",
            """{"type":"response.completed","response":{"status":"completed","output":[{"type":"$metadata"},$message],"usage":{"input_tokens":5,"output_tokens":3,"total_tokens":8}}}""",
        )
    }

    private fun request(model: String) = AiRequest(
        AiModel(model),
        listOf(AiMessage(AiRole.USER, listOf(AiContentPart.Text("Say hello")))),
    )

    private fun sse(vararg frames: String) = frames.joinToString("") { "data: $it\n\n" }

    private data class StreamCase(
        val model: String,
        val firstDelta: String,
        val events: String,
        val create: (HttpTransport) -> StreamingAiProvider,
    )
}
