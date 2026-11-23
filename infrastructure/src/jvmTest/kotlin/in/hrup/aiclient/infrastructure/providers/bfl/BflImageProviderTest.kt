package `in`.hrup.aiclient.infrastructure.providers.bfl

import `in`.hrup.aiclient.AiClient
import `in`.hrup.aiclient.AiClientConfig
import `in`.hrup.aiclient.AiProviderConfig
import `in`.hrup.aiclient.AiProviderId
import `in`.hrup.aiclient.MissingAiApiKeyException
import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.model.conversation.AiImageSource
import `in`.hrup.aiclient.model.image.AiImageFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BflImageProviderTest {
    @Test
    fun submitsAndPollsUntilImageIsReady() = runBlocking {
        var calls = 0
        val resultBody = """{"id":"job-1","status":"Ready","cost":5.5,"result":{"sample":"https://delivery.eu.bfl.ai/image.png"}}"""
        HttpClient(MockEngine { request ->
            assertEquals("test-key", request.headers["x-key"])
            when (++calls) {
                1 -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("https://api.bfl.ai/v1/flux-2-pro", request.url.toString())
                    assertEquals(
                        """{"prompt":"A blue square"}""",
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
                    )
                    respond("""{"id":"job-1","polling_url":"https://api.eu.bfl.ai/v1/get_result?id=job-1","cost":6}""")
                }
                else -> {
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("https://api.eu.bfl.ai/v1/get_result?id=job-1", request.url.toString())
                    respond(if (calls == 2) """{"id":"job-1","status":"Pending"}""" else resultBody)
                }
            }
        }).use { http ->
            val client = AiClient(http, AiClientConfig(
                providers = mapOf(AiProviderId.BFL to AiProviderConfig("test-key", "flux-2-pro")),
            ))
            val result = client.generateImage("A blue square")
            assertEquals("bfl", result.provider)
            assertEquals("flux-2-pro", result.model)
            assertEquals("job-1", result.requestId)
            assertEquals("https://delivery.eu.bfl.ai/image.png", result.imageUrl)
            assertEquals("""{"prompt":"A blue square"}""", result.request)
            assertEquals(resultBody, result.response)
            assertEquals(5.5, result.costCredits)
            assertEquals(3, calls)
        }
    }

    @Test
    fun sendsImageReferencesInOrderUsingTheModelsWireFormat() = runBlocking {
        val references = listOf(
            AiImageSource.Url("https://images.example/reference.jpg"),
            AiImageSource.Base64("AA==", "image/png"),
        )
        for ((model, payload) in mapOf(
            "flux-3-image-klein" to """{"prompt":"Edit this","output_format":"png","width":1024,"height":768,"images":["https://images.example/reference.jpg","AA=="]}""",
            "flux-2-pro" to """{"prompt":"Edit this","output_format":"jpeg","width":1024,"height":768,"input_image":"https://images.example/reference.jpg","input_image_2":"AA=="}""",
        )) {
            HttpClient(MockEngine { request ->
                if (request.method == HttpMethod.Post) {
                    val actual = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    assertEquals(Json.parseToJsonElement(payload), Json.parseToJsonElement(actual))
                    respond("""{"id":"job-1","polling_url":"https://api.bfl.ai/v1/get_result?id=job-1","cost":3}""")
                } else {
                    respond("""{"id":"job-1","status":"Ready","result":{"sample":"https://delivery.eu.bfl.ai/image.png"}}""")
                }
            }).use { http ->
                val client = AiClient(http, AiClientConfig(
                    providers = mapOf(AiProviderId.BFL to AiProviderConfig("test-key")),
                ))
                val format = if (model == "flux-3-image-klein") AiImageFormat.PNG else AiImageFormat.JPEG
                val result = client.generateImage("Edit this", references, model, format, width = 1024, height = 768)
                assertEquals(model, result.model)
                assertEquals(3.0, result.costCredits)
            }
        }
    }

    @Test
    fun sendsDimensionsForTextToImageWithTheDefaultModel() = runBlocking {
        val payload = Json.parseToJsonElement("""{"prompt":"A blue rectangle","width":1024,"height":768}""")
        HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                assertEquals("/v1/flux-3-image", request.url.encodedPath)
                val actual = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                assertEquals(payload, Json.parseToJsonElement(actual))
                respond("""{"id":"job-1","polling_url":"https://api.bfl.ai/v1/get_result?id=job-1"}""")
            } else {
                respond("""{"id":"job-1","status":"Ready","result":{"sample":"https://delivery.eu.bfl.ai/image.jpg"}}""")
            }
        }).use { http ->
            val client = AiClient(http, AiClientConfig(
                providers = mapOf(AiProviderId.BFL to AiProviderConfig("test-key")),
            ))
            val result = client.generateImage("A blue rectangle", width = 1017, height = 775)
            assertEquals(payload, Json.parseToJsonElement(result.request))
        }
    }

    @Test
    fun normalizesFlux3DimensionsToTheModelLimits() = runBlocking {
        HttpClient(MockEngine { request ->
            respond(if (request.method == HttpMethod.Post) {
                """{"id":"job-1","polling_url":"https://api.bfl.ai/v1/get_result?id=job-1"}"""
            } else {
                """{"id":"job-1","status":"Ready","result":{"sample":"https://delivery.eu.bfl.ai/image.jpg"}}"""
            })
        }).use { http ->
            val adapter = BflImageProvider(HttpTransport(http), "test-key")
            val references = listOf(AiImageSource.Url("https://images.example/reference.jpg"))
            val results = listOf(
                adapter.generate("A blue rectangle", width = 20, height = 3000) to (256 to 2048),
                adapter.generate("A blue rectangle", "flux-3-image-klein", width = Int.MAX_VALUE, height = 20) to (6256 to 64),
                adapter.generate("A blue square", width = 6256, height = 6256) to (4096 to 4096),
                adapter.generate("Edit this", references, width = 4096, height = 4096) to (2048 to 2048),
                adapter.generate("Edit this", references, "flux-3-image-klein", width = 4096, height = 4096) to (4096 to 4096),
            )
            for ((result, expected) in results) {
                val request = Json.parseToJsonElement(result.request).jsonObject
                val actual = request.getValue("width").jsonPrimitive.int to request.getValue("height").jsonPrimitive.int
                assertEquals(expected, actual)
            }
        }
    }

    @Test
    fun rejectsInvalidDimensionsBeforeSubmitting() = runBlocking {
        HttpClient(MockEngine { error("Unexpected HTTP request") }).use { http ->
            val adapter = BflImageProvider(HttpTransport(http), "test-key")
            assertFailsWith<IllegalArgumentException> {
                adapter.generate("A blue rectangle", width = 1024)
            }
            val flux2Error = assertFailsWith<IllegalArgumentException> {
                adapter.generate("A blue rectangle", model = "flux-2-pro", width = 63)
            }
            assertTrue(flux2Error.message.orEmpty().contains("at least 64"))
        }
    }

    @Test
    fun rejectsExplicitOutputFormatForFlux3Image() = runBlocking {
        HttpClient(MockEngine { error("Unexpected HTTP request") }).use { http ->
            val client = AiClient(http, AiClientConfig(
                providers = mapOf(AiProviderId.BFL to AiProviderConfig("test-key")),
            ))
            val error = assertFailsWith<IllegalArgumentException> {
                client.generateImage("A blue square", outputFormat = AiImageFormat.PNG)
            }
            assertTrue(error.message.orEmpty().contains("does not support outputFormat"))
        }
    }

    @Test
    fun rejectsTooManyKleinReferencesBeforeSubmitting() = runBlocking {
        HttpClient(MockEngine { error("Unexpected HTTP request") }).use { http ->
            val adapter = BflImageProvider(HttpTransport(http), "test-key")
            val references = List(5) { AiImageSource.Url("https://images.example/reference.jpg") }
            val error = assertFailsWith<IllegalArgumentException> {
                adapter.generate("Edit this", references, "flux-2-klein-9b")
            }
            assertTrue(error.message.orEmpty().contains("at most 4"))
        }
    }

    @Test
    fun rejectsAnUntrustedPollingUrlWithoutForwardingTheKey() = runBlocking {
        HttpClient(MockEngine { request ->
            assertEquals("api.bfl.ai", request.url.host)
            respond("""{"id":"job-1","polling_url":"https://other.example/v1/get_result?id=job-1"}""")
        }).use { http ->
            val adapter = BflImageProvider(HttpTransport(http), "test-key")
            val error = assertFailsWith<BflImageException> { adapter.generate("A blue square") }
            assertTrue(error.message.orEmpty().contains("untrusted polling URL"))
        }
    }

    @Test
    fun returnsTheProviderErrorForATerminalTask() = runBlocking {
        HttpClient(MockEngine { request ->
            respond(if (request.method == HttpMethod.Post) {
                """{"id":"job-1","polling_url":"https://api.bfl.ai/v1/get_result?id=job-1"}"""
            } else {
                """{"id":"job-1","status":"Request Moderated","details":{"reason":"blocked"}}"""
            })
        }).use { http ->
            val adapter = BflImageProvider(HttpTransport(http), "test-key")
            val error = assertFailsWith<BflImageException> { adapter.generate("A blue square") }
            assertTrue(error.message.orEmpty().contains("Request Moderated"))
            assertTrue(error.responseBody.contains("blocked"))
        }
    }

    @Test
    fun failsWithoutAKeyBeforeNetworkIo() = runBlocking {
        HttpClient(MockEngine { error("Unexpected HTTP request") }).use { http ->
            val error = assertFailsWith<MissingAiApiKeyException> {
                AiClient(http, AiClientConfig()).generateImage("A blue square")
            }
            assertEquals(AiProviderId.BFL, error.provider)
        }
    }
}
