package `in`.hrup.aiclient.infrastructure.http

import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiImageSource
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.generation.AiRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlin.io.encoding.Base64

internal suspend fun AiRequest.inlineRemoteImages(
    transport: HttpTransport,
    supportedMediaTypes: Set<String>,
): AiRequest = copy(input = input.map { item ->
    if (item !is AiMessage) return@map item
    item.copy(content = item.content.map { part ->
        val source = (part as? AiContentPart.Image)?.source as? AiImageSource.Url
        if (source == null) part else AiContentPart.Image(transport.loadImage(source.url, supportedMediaTypes))
    })
})

private suspend fun HttpTransport.loadImage(url: String, supportedMediaTypes: Set<String>): AiImageSource.Base64 {
    val parsedUrl = Url(url)
    require(
        (parsedUrl.protocol == URLProtocol.HTTP || parsedUrl.protocol == URLProtocol.HTTPS) &&
            (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)) &&
            parsedUrl.host.isNotBlank() && parsedUrl.user == null && parsedUrl.password == null,
    ) { "Image URL must be an absolute HTTP(S) URL without credentials" }

    val limit = 32 * 1024 * 1024
    var mediaType: String? = null
    var size = 0
    val chunks = mutableListOf<ByteArray>()
    stream(url).collect { part ->
        when (part) {
            is HttpStreamPart.Head -> {
                check(part.value.status.value in 200..299) {
                    "Image download returned HTTP ${part.value.status.value}"
                }
                val type = part.value.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
                check(type in supportedMediaTypes) { "Image download returned unsupported media type: $type" }
                mediaType = type
                val length = part.value.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                check(length == null || length <= limit) { "Image download exceeds 32 MiB" }
            }
            is HttpStreamPart.Chunk -> {
                check(part.bytes.size <= limit - size) { "Image download exceeds 32 MiB" }
                chunks += part.bytes
                size += part.bytes.size
            }
        }
    }
    check(size > 0) { "Image download returned an empty body" }
    val data = ByteArray(size)
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(data, offset)
        offset += chunk.size
    }
    return AiImageSource.Base64(Base64.encode(data), checkNotNull(mediaType))
}
