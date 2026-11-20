package `in`.hrup.aiclient.infrastructure.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpTransportTest {
    @Test
    fun bufferedResponsePreservesErrorStatusAndBody() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("payload", assertIs<OutgoingContent.ByteArrayContent>(request.body).bytes().decodeToString())
            respond("denied", HttpStatusCode.Unauthorized, headersOf("X-Request-Id", "request-1"))
        }
        HttpClient(engine) { expectSuccess = true }.use { client ->
            val response = HttpTransport(client).execute("https://example.test") {
                method = HttpMethod.Post
                setBody("payload")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.head.status)
            assertEquals("request-1", response.head.headers["X-Request-Id"])
            assertEquals("denied", response.body.decodeToString())
        }
    }

    @Test
    fun streamIsColdAndCanBeCollectedAgainAfterEarlyTermination() = runBlocking {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("поток".repeat(4_000), HttpStatusCode.OK)
        }
        HttpClient(engine).use { client ->
            val stream = HttpTransport(client).stream("https://example.test")
            assertEquals(0, requests)
            val first = assertIs<HttpStreamPart.Head>(stream.take(1).toList().single())
            assertEquals(HttpStatusCode.OK, first.value.status)
            val parts = stream.toList()
            assertEquals(2, requests)
            assertIs<HttpStreamPart.Head>(parts.first())
            val bytes = parts.drop(1).flatMap { assertIs<HttpStreamPart.Chunk>(it).bytes.asIterable() }.toByteArray()
            assertEquals("поток".repeat(4_000), bytes.decodeToString())
        }
    }
}
