package `in`.hrup.aiclient.infrastructure.providers.text

import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiImageSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun responsesContent(parts: List<AiContentPart>): JsonElement =
    messageContent(parts, "input_text") { source ->
        buildJsonObject {
            put("type", "input_image")
            put("image_url", source.toImageUrl())
        }
    }

internal fun chatContent(parts: List<AiContentPart>, provider: String, allowsRemoteUrls: Boolean): JsonElement =
    messageContent(parts, "text") { source ->
        require(allowsRemoteUrls || source !is AiImageSource.Url) {
            "$provider does not accept remote image URLs. Pass AiImageSource.Base64 instead."
        }
        buildJsonObject {
            put("type", "image_url")
            putJsonObject("image_url") { put("url", source.toImageUrl()) }
        }
    }

private fun messageContent(
    parts: List<AiContentPart>,
    textType: String,
    imagePart: (AiImageSource) -> JsonElement,
): JsonElement {
    if (parts.all { it is AiContentPart.Text }) {
        return JsonPrimitive(parts.joinToString("") { (it as AiContentPart.Text).text })
    }
    return buildJsonArray {
        for (part in parts) add(when (part) {
            is AiContentPart.Text -> buildJsonObject {
                put("type", textType)
                put("text", part.text)
            }
            is AiContentPart.Image -> imagePart(part.source)
        })
    }
}

private fun AiImageSource.toImageUrl(): String = when (this) {
    is AiImageSource.Url -> url.also {
        require(it.isNotBlank()) { "Image URL must not be blank" }
    }
    is AiImageSource.Base64 -> {
        require(mediaType.matches(Regex("image/[A-Za-z0-9.+-]+"))) { "Image media type must be image/*" }
        require(data.isNotBlank()) { "Base64 image data must not be blank" }
        "data:$mediaType;base64,$data"
    }
}
