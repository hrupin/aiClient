package `in`.hrup.aiclient.infrastructure.http.sse

import `in`.hrup.aiclient.infrastructure.http.HttpResponseHead
import `in`.hrup.aiclient.infrastructure.http.HttpStreamPart
import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile

data class SseEvent(val data: String, val type: String = "message", val id: String = "")

class SseResponseException(val head: HttpResponseHead) :
    IllegalStateException("Expected an SSE response, received HTTP ${head.status.value} with Content-Type ${head.headers[HttpHeaders.ContentType]}")

/** Reads one SSE response per collection. HTTP 204 and EOF complete the flow. */
fun HttpTransport.sse(
    url: String,
    configure: HttpRequestBuilder.() -> Unit = {},
): Flow<SseEvent> = flow {
    val decoder = SseDecoder()
    stream(url) {
        accept(ContentType.Text.EventStream)
        configure()
    }.takeWhile { part ->
        part !is HttpStreamPart.Head || part.value.status != HttpStatusCode.NoContent
    }.collect { part ->
        when (part) {
            is HttpStreamPart.Head -> {
                val mediaType = part.value.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
                if (part.value.status != HttpStatusCode.OK ||
                    !ContentType.Text.EventStream.toString().equals(mediaType, ignoreCase = true)
                ) {
                    throw SseResponseException(part.value)
                }
            }
            is HttpStreamPart.Chunk -> decoder.decode(part.bytes) { emit(it) }
        }
    }
}
