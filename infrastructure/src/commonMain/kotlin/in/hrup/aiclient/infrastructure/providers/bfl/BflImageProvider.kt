package `in`.hrup.aiclient.infrastructure.providers.bfl

import `in`.hrup.aiclient.infrastructure.http.BufferedHttpResponse
import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.model.conversation.AiImageSource
import `in`.hrup.aiclient.model.image.AiImageFormat
import `in`.hrup.aiclient.model.image.AiImageResponse
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class BflImageException(
    message: String,
    val httpStatus: Int,
    val responseBody: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class BflImageProvider(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val baseUrl: String = "https://api.bfl.ai/v1",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val apiBase = Url(baseUrl)

    init {
        require(apiKey.isNotBlank()) { "BFL API key must not be blank" }
        require(apiBase.protocol == URLProtocol.HTTPS && apiBase.host.isNotBlank() &&
            apiBase.user == null && apiBase.password == null && apiBase.parameters.isEmpty() &&
            apiBase.fragment.isEmpty()
        ) { "BFL base URL must be an HTTPS API base URL" }
    }

    suspend fun generate(
        prompt: String,
        model: String = "flux-3-image",
        outputFormat: AiImageFormat? = null,
    ): AiImageResponse = generate(prompt, emptyList(), model, outputFormat)

    suspend fun generate(
        prompt: String,
        images: List<AiImageSource>,
        model: String = "flux-3-image",
        outputFormat: AiImageFormat? = null,
    ): AiImageResponse {
        require(prompt.isNotBlank()) { "Image prompt must not be blank" }
        require(model.matches(Regex("flux-[23]-(?!video(?:-|$))[a-z0-9-]+"))) { "Invalid BFL image model: $model" }
        require(outputFormat == null || model != "flux-3-image") {
            "flux-3-image does not support outputFormat. Use flux-3-image-klein or a FLUX.2 image model."
        }
        val request = imageRequestBody(prompt, images, model, outputFormat)
        return withTimeout(120_000) {
            val submission = transport.execute("${baseUrl.trimEnd('/')}/$model") {
                method = HttpMethod.Post
                header("x-key", apiKey)
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            val task = submission.decode<SubmittedImage>()
            if (task.id.isBlank()) submission.fail("BFL response is missing a task ID")
            if (!isTrustedPollingUrl(task.pollingUrl)) submission.fail("BFL returned an untrusted polling URL")
            awaitImage(task, model, request)
        }
    }

    private fun imageRequestBody(
        prompt: String,
        images: List<AiImageSource>,
        model: String,
        outputFormat: AiImageFormat?,
    ): String {
        val maxImages = when {
            model.startsWith("flux-3-") -> 10
            model.startsWith("flux-2-klein-") -> 4
            else -> 8
        }
        require(images.size <= maxImages) { "$model accepts at most $maxImages reference images" }
        val references = images.map { it.toBflImage() }
        return buildJsonObject {
            put("prompt", prompt)
            outputFormat?.let { put("output_format", it.name.lowercase()) }
            if (references.isNotEmpty()) {
                if (model.startsWith("flux-3-")) {
                    putJsonArray("images") { references.forEach { add(it) } }
                } else {
                    references.forEachIndexed { index, reference ->
                        val key = if (index == 0) "input_image" else "input_image_${index + 1}"
                        put(key, reference)
                    }
                }
            }
        }.toString()
    }

    private fun AiImageSource.toBflImage(): String = when (this) {
        is AiImageSource.Url -> url.also {
            require(it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)) {
                "BFL reference image URL must be an HTTP(S) URL"
            }
            val parsed = Url(it)
            require(parsed.host.isNotBlank() && parsed.user == null && parsed.password == null) {
                "BFL reference image URL must be absolute and have no credentials"
            }
        }
        is AiImageSource.Base64 -> data.also {
            require(mediaType.startsWith("image/") && it.isNotBlank() && !it.startsWith("data:", ignoreCase = true)) {
                "BFL Base64 references require raw Base64 image data and an image media type"
            }
        }
    }

    private suspend fun awaitImage(task: SubmittedImage, model: String, request: String): AiImageResponse {
        while (true) {
            delay(500)
            val response = transport.execute(task.pollingUrl) {
                method = HttpMethod.Get
                header("x-key", apiKey)
                accept(ContentType.Application.Json)
            }
            val status = response.decode<ImageTaskStatus>()
            if (status.id != task.id) response.fail("BFL polling response has a different task ID")
            when (status.status) {
                "Pending", "Reasoning", "Generating" -> continue
                "Ready" -> {
                    val sample = status.result?.sample?.takeIf(String::isNotBlank)
                        ?: response.fail("BFL completed the task without an image URL")
                    return AiImageResponse(
                        provider = "bfl",
                        model = model,
                        requestId = task.id,
                        imageUrl = sample,
                        request = request,
                        response = response.body.decodeToString(),
                        costCredits = status.cost ?: task.cost,
                    )
                }
                else -> response.fail("BFL image generation ended with status ${status.status}")
            }
        }
    }

    private fun isTrustedPollingUrl(value: String): Boolean {
        if (!value.startsWith("https://", ignoreCase = true)) return false
        val target = try {
            Url(value)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (target.protocol != URLProtocol.HTTPS || target.user != null || target.password != null) return false
        if (target.host.equals(apiBase.host, ignoreCase = true) && target.port == apiBase.port) return true
        return apiBase.host.endsWith(".bfl.ai", ignoreCase = true) &&
            target.host.endsWith(".bfl.ai", ignoreCase = true) && target.port == 443
    }

    private inline fun <reified T> BufferedHttpResponse.decode(): T {
        if (head.status.value !in 200..299) fail("BFL returned HTTP ${head.status.value}")
        return try {
            json.decodeFromString(body.decodeToString())
        } catch (cause: SerializationException) {
            throw BflImageException("Invalid BFL image response", head.status.value, body.decodeToString(), cause)
        }
    }

    private fun BufferedHttpResponse.fail(message: String): Nothing =
        throw BflImageException(message, head.status.value, body.decodeToString())
}

@Serializable
private data class SubmittedImage(
    val id: String,
    @SerialName("polling_url") val pollingUrl: String,
    val cost: Double? = null,
)

@Serializable
private data class ImageTaskStatus(
    val id: String,
    val status: String,
    val result: ImageResult? = null,
    val cost: Double? = null,
)

@Serializable
private data class ImageResult(val sample: String? = null)
