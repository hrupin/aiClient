package `in`.hrup.aiclient.infrastructure.http

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onEach

data class HttpResponseHead(val status: HttpStatusCode, val headers: Headers)

data class BufferedHttpResponse(val head: HttpResponseHead, val body: ByteArray)

sealed interface HttpStreamPart {
    data class Head(val value: HttpResponseHead) : HttpStreamPart
    data class Chunk(val bytes: ByteArray) : HttpStreamPart
}

/** Borrows [client]. Its owner is responsible for closing it. */
class HttpTransport internal constructor(
    private val client: HttpClient,
    private val capture: HttpExchangeCapture?,
) {
    constructor(client: HttpClient) : this(client, null)

    internal fun withoutExchangeCapture(): HttpTransport =
        if (capture == null) this else HttpTransport(client)

    /** Returns the response for any HTTP status, including error statuses. */
    suspend fun execute(
        url: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): BufferedHttpResponse = prepare(url, configure).execute { response ->
        capture?.begin(response.call.request.content)
        val body = response.bodyAsBytes()
        capture?.append(body)
        BufferedHttpResponse(HttpResponseHead(response.status, response.headers), body)
    }

    /** Each collection opens a request and emits its headers before any body bytes. */
    fun stream(
        url: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): Flow<HttpStreamPart> = channelFlow {
        prepare(url, configure).execute { response ->
            capture?.begin(response.call.request.content)
            send(HttpStreamPart.Head(HttpResponseHead(response.status, response.headers)))
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count == -1) break
                if (count > 0) send(HttpStreamPart.Chunk(buffer.copyOf(count)))
            }
            channel.closedCause?.let { throw it }
        }
    }.buffer(0).onEach { part ->
        if (part is HttpStreamPart.Chunk) capture?.append(part.bytes)
    }

    private suspend fun prepare(url: String, configure: HttpRequestBuilder.() -> Unit) =
        client.prepareRequest(url) {
            configure()
            expectSuccess = false
        }
}
