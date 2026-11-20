package `in`.hrup.aiclient.infrastructure.providers.bfl

import `in`.hrup.aiclient.AiClient
import `in`.hrup.aiclient.AiClientConfig
import `in`.hrup.aiclient.AiProviderConfig
import `in`.hrup.aiclient.AiProviderId
import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.http.createHttpClient
import `in`.hrup.aiclient.infrastructure.providers.liveSetting
import `in`.hrup.aiclient.infrastructure.providers.readLiveEnv
import `in`.hrup.aiclient.model.conversation.AiImageSource
import `in`.hrup.aiclient.model.image.AiImageFormat
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BflLiveIntegrationTest {
    @Test
    fun generatesAnImage() = runBlocking {
        val env = readLiveEnv()
        val key = checkNotNull(liveSetting(System.getenv("BFL_API_KEY"), env["BFL_API_KEY"])) {
            "Set BFL_API_KEY to run the live BFL test"
        }
        val model = liveSetting(System.getProperty("aiModel"), System.getenv("BFL_MODEL"), env["BFL_MODEL"])
        createHttpClient().use { http ->
            val client = AiClient(http, AiClientConfig(
                providers = mapOf(AiProviderId.BFL to AiProviderConfig(apiKey = key)),
            ))
            val result = client.generateImage("A small blue ceramic cup on a plain white background.", model)
            assertTrue(result.imageUrl.startsWith("https://"))
            println("MODEL=${result.model} COST_CREDITS=${result.costCredits} IMAGE_URL=${result.imageUrl}")
        }
    }

    @Test
    fun generatesPng() = runBlocking {
        val key = checkNotNull(liveSetting(System.getenv("BFL_API_KEY"), readLiveEnv()["BFL_API_KEY"])) {
            "Set BFL_API_KEY to run the live BFL test"
        }
        createHttpClient().use { http ->
            val client = AiClient(http, AiClientConfig(
                providers = mapOf(AiProviderId.BFL to AiProviderConfig(apiKey = key)),
            ))
            val result = client.generateImage(
                "A small blue ceramic cup on a plain white background.",
                model = "flux-3-image-klein",
                outputFormat = AiImageFormat.PNG,
            )
            assertImageFormat(HttpTransport(http), result.imageUrl, AiImageFormat.PNG)
        }
    }

    @Test
    fun editsImageFromUrl() = runBlocking { edit("flux-2-pro", inline = false) }

    @Test
    fun editsImageFromBase64() = runBlocking { edit("flux-3-image-klein", inline = true) }

    private suspend fun edit(model: String, inline: Boolean) {
        val key = checkNotNull(liveSetting(System.getenv("BFL_API_KEY"), readLiveEnv()["BFL_API_KEY"])) {
            "Set BFL_API_KEY to run the live BFL test"
        }
        val sourceUrl = "https://cdn.sanity.io/images/2gpum2i6/production/ae68491f9136ce25fc5c8d91830a778d73340c10-1800x1350.webp"
        createHttpClient().use { http ->
            val source = if (inline) {
                val response = HttpTransport(http).execute(sourceUrl)
                assertTrue(response.head.status.value == 200)
                AiImageSource.Base64(Base64.getEncoder().encodeToString(response.body), "image/webp")
            } else AiImageSource.Url(sourceUrl)
            val client = AiClient(http, AiClientConfig(
                providers = mapOf(AiProviderId.BFL to AiProviderConfig(apiKey = key)),
            ))
            val outputFormat = if (inline) AiImageFormat.PNG else AiImageFormat.JPEG
            val result = client.generateImage(
                "Turn this lakeside scene into a winter evening with snow and warm light from the cabin windows.",
                images = listOf(source),
                model = model,
                outputFormat = outputFormat,
            )
            assertImageFormat(HttpTransport(http), result.imageUrl, outputFormat)
            println("MODEL=${result.model} COST_CREDITS=${result.costCredits} IMAGE_URL=${result.imageUrl}")
        }
    }

    private suspend fun assertImageFormat(transport: HttpTransport, url: String, format: AiImageFormat) {
        assertTrue(url.startsWith("https://"))
        val response = transport.execute(url)
        assertEquals(200, response.head.status.value)
        val signature = when (format) {
            AiImageFormat.PNG -> byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            AiImageFormat.JPEG -> byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        }
        assertContentEquals(signature, response.body.take(signature.size).toByteArray())
        println("FORMAT=$format")
    }

}
