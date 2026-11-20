package `in`.hrup.aiclient.infrastructure.http.sse

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SseTest {
    private val eventStreamHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream; charset=utf-8")

    @Test
    fun handlesUtf8AndLineEndingsSplitAcrossChunks() = runBlocking {
        val events = mutableListOf<SseEvent>()
        val input = "\uFEFF: start\r\nid: 7\revent: update\ndata: привет\r\ndata: 🌍\r\n\r\ndata\n\n"
        val decoder = SseDecoder()
        for (byte in input.encodeToByteArray()) {
            decoder.decode(byteArrayOf(byte)) { events.add(it) }
        }
        assertEquals(listOf(SseEvent("привет\n🌍", "update", "7"), SseEvent("", id = "7")), events)
    }

    @Test
    fun eofCompletesAndDiscardsUnterminatedEvent() = runBlocking {
        HttpClient(MockEngine {
            assertEquals("text/event-stream", it.headers[HttpHeaders.Accept])
            respond("data: ready\n\ndata: partial\n", headers = eventStreamHeaders)
        }).use { client ->
            assertEquals(listOf(SseEvent("ready")), HttpTransport(client).sse("https://example.test").toList())
        }
    }

    @Test
    fun earlyCancellationClosesOpenResponse() = runBlocking {
        val response = ByteChannel(autoFlush = true)
        response.writeStringUtf8("data: ready\n\n")
        HttpClient(MockEngine { respond(response, headers = eventStreamHeaders) }).use { client ->
            assertEquals(SseEvent("ready"), withTimeout(5_000) {
                HttpTransport(client).sse("https://example.test").first()
            })
            assertTrue(response.isClosedForWrite)
        }
    }

    @Test
    fun noContentCompletesAndHttpErrorPreservesStatus() = runBlocking {
        HttpClient(MockEngine { request ->
            if (request.url.segments.lastOrNull() == "empty") respond("", HttpStatusCode.NoContent)
            else respond("denied", HttpStatusCode.Unauthorized)
        }).use { client ->
            val transport = HttpTransport(client)
            assertEquals(emptyList(), transport.sse("https://example.test/empty").toList())
            val error = assertFailsWith<SseResponseException> {
                transport.sse("https://example.test/error").toList()
            }
            assertEquals(HttpStatusCode.Unauthorized, error.head.status)
        }
    }
}
