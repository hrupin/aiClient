package `in`.hrup.aiclient.infrastructure.http

import io.ktor.http.content.OutgoingContent

/** Captures the last provider exchange of a single generation. */
internal class HttpExchangeCapture {
    var requestBody: String = ""
        private set

    private val chunks = mutableListOf<ByteArray>()

    fun begin(body: OutgoingContent) {
        requestBody = (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
        chunks.clear()
    }

    fun append(bytes: ByteArray) {
        chunks += bytes
    }

    fun responseBody(): String {
        chunks.singleOrNull()?.let { return it.decodeToString() }
        val body = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(body, offset)
            offset += chunk.size
        }
        return body.decodeToString()
    }
}
