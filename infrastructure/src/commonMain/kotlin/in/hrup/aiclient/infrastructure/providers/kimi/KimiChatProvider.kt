package `in`.hrup.aiclient.infrastructure.providers.kimi

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.http.inlineRemoteImages
import `in`.hrup.aiclient.infrastructure.providers.text.ChatCompletionsText
import `in`.hrup.aiclient.infrastructure.providers.text.TextProviderHttp
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.provider.StreamingAiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class KimiChatProvider(
    transport: HttpTransport,
    apiKey: String,
    endpoint: String = "https://api.moonshot.ai/v1/chat/completions",
) : StreamingAiProvider {
    private val chat = ChatCompletionsText(
        TextProviderHttp("Kimi", transport, endpoint, apiKey),
        nativeSchemaModels = setOf("kimi-k3"),
        allowsRemoteImageUrls = false,
    )

    private val imageTransport = transport.withoutExchangeCapture()
    private val imageMediaTypes = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/heic", "image/heif",
    )

    override suspend fun generate(request: AiRequest): AiResponse =
        chat.generate(request.inlineRemoteImages(imageTransport, imageMediaTypes), normalizer(request))

    override fun stream(request: AiRequest): Flow<AiStreamEvent> = flow {
        emitAll(chat.stream(request.inlineRemoteImages(imageTransport, imageMediaTypes), normalizer(request)))
    }

    private fun normalizer(request: AiRequest): ((String) -> String)? =
        if (request.model.id == "kimi-k3") ::unwrapSingletonObject else null

    private fun unwrapSingletonObject(text: String): String {
        if (!text.trimStart().startsWith('[')) return text
        val element = try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return text
        }
        // K3 sometimes encloses an otherwise valid object in a singleton array.
        val inner = (element as? JsonArray)?.singleOrNull() as? JsonObject
        return inner?.toString() ?: text
    }
}
