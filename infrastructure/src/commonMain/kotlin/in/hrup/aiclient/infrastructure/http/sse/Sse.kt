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

class SseResponseException(val head: HttpResponseHead, val errorBody: String?) :
    IllegalStateException("Expected an SSE response, received HTTP ${head.status.value} with Content-Type ${head.headers[HttpHeaders.ContentType]}") {
    constructor(head: HttpResponseHead) : this(head, null)
}

private const val MAX_SSE_ERROR_BYTES = 16 * 1024

/** Reads one SSE response per collection. HTTP 204 and EOF complete the flow. */
fun HttpTransport.sse(
    url: String,
    configure: HttpRequestBuilder.() -> Unit = {},
): Flow<SseEvent> = flow {
    val decoder = SseDecoder()
    var errorHead: HttpResponseHead? = null
    var errorBytes: ByteArray? = null
    var errorSize = 0
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
                    val json = mediaType?.lowercase()?.let { it == "application/json" || it.endsWith("+json") } == true
                    if (part.value.status.value !in 400..599 || !json) throw SseResponseException(part.value)
                    errorHead = part.value
                    errorBytes = ByteArray(MAX_SSE_ERROR_BYTES)
                }
            }
            is HttpStreamPart.Chunk -> {
                val buffer = errorBytes
                if (buffer == null) {
                    decoder.decode(part.bytes) { emit(it) }
                } else {
                    if (part.bytes.size > buffer.size - errorSize) throw SseResponseException(checkNotNull(errorHead))
                    part.bytes.copyInto(buffer, errorSize)
                    errorSize += part.bytes.size
                }
            }
        }
    }
    errorHead?.let { throw SseResponseException(it, checkNotNull(errorBytes).decodeToString(endIndex = errorSize)) }
}
