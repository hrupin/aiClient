package `in`.hrup.aiclient.infrastructure.providers.openai

import `in`.hrup.aiclient.AiClient
import `in`.hrup.aiclient.AiClientConfig
import `in`.hrup.aiclient.AiProviderConfig
import `in`.hrup.aiclient.AiProviderId
import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.http.createHttpClient
import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiImageSource
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.model.value.AiValue
import `in`.hrup.aiclient.infrastructure.providers.liveSetting
import `in`.hrup.aiclient.infrastructure.providers.readLiveEnv
import java.util.Base64
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class OpenAiVisionIntegrationTest(source: String) : VisionIntegrationCases(AiProviderId.OPENAI, null, source) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun sources(): List<String> = listOf("URL", "Base64")
    }
}

@RunWith(Parameterized::class)
class ProviderVisionIntegrationTest(provider: AiProviderId, model: String, source: String) :
    VisionIntegrationCases(provider, model, source) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}/{2}")
        fun cases(): List<Array<Any>> = listOf(
            AiProviderId.DEEPSEEK to "deepseek-flash",
            AiProviderId.XAI to "grok-4.7",
            AiProviderId.ZAI to "glm-5v-turbo",
            AiProviderId.KIMI to "kimi-k3.1-pro",
            AiProviderId.PERPLEXITY to "openai/gpt-5.6-luna",
        ).flatMap { (provider, model) ->
            listOf("URL", "Base64").map { source -> arrayOf<Any>(provider, model, source) }
        }
    }
}

abstract class VisionIntegrationCases(
    private val provider: AiProviderId,
    private val modelOverride: String?,
    private val source: String,
) {
    @Test
    fun bufferedText() = runBlocking { analyze(streaming = false, structured = false) }

    @Test
    fun bufferedJsonSchema() = runBlocking { analyze(streaming = false, structured = true) }

    @Test
    fun streamedText() = runBlocking { analyze(streaming = true, structured = false) }

    @Test
    fun streamedJsonSchema() = runBlocking { analyze(streaming = true, structured = true) }

    private suspend fun analyze(streaming: Boolean, structured: Boolean) {
        val (apiKey, model) = if (provider == AiProviderId.OPENAI) {
            val settings = loadOpenAiLiveSettings()
            settings.apiKey to settings.model
        } else {
            val keyName = provider.apiKeySetting
            val key = checkNotNull(liveSetting(System.getenv(keyName), readLiveEnv()[keyName])) {
                "Set $keyName to run the vision integration test"
            }
            key to requireNotNull(modelOverride)
        }
        val format = if (structured) parseJsonResponseFormat(
            "image_analysis",
            """{"type":"object","properties":{"jacket_pattern":{"type":"string","enum":["striped","plain","checked"]},"holding_flowers":{"type":"boolean"}},"required":["jacket_pattern","holding_flowers"],"additionalProperties":false}""",
        ) else null
        createHttpClient().use { http ->
            val result = withTimeout(90_000) {
                val image = if (source == "URL") AiImageSource.Url(imageUrl) else {
                    val downloaded = HttpTransport(http).execute(imageUrl)
                    assertEquals(200, downloaded.head.status.value)
                    AiImageSource.Base64(Base64.getEncoder().encodeToString(downloaded.body), "image/jpeg")
                }
                val prompt = if (structured) "Identify the pattern on the subject's jacket and whether the subject is holding flowers."
                    else "In one short sentence, describe the pattern on the subject's jacket and what the subject is holding."
                val request = AiRequest(
                    model = AiModel(model),
                    input = listOf(AiMessage(AiRole.USER, listOf(
                        AiContentPart.Text(prompt), AiContentPart.Image(image),
                    ))),
                    responseFormat = format,
                )
                val client = AiClient(http, AiClientConfig(
                    defaultProvider = provider,
                    providers = mapOf(provider to AiProviderConfig(apiKey)),
                ))
                if (streaming) {
                    val events = client.stream(request).toList()
                    val response = assertIs<AiStreamEvent.Completed>(events.last()).response
                    val deltas = events.filterIsInstance<AiStreamEvent.TextDelta>()
                    assertTrue(deltas.isNotEmpty())
                    assertEquals(response.content, deltas.joinToString("") { it.text })
                    response
                } else {
                    client.generate(request)
                }
            }
            if (provider == AiProviderId.KIMI) {
                assertTrue("data:image/jpeg;base64," in result.request)
                assertTrue(imageUrl !in result.request)
            }
            println("MODEL=${result.model} ANSWER=${result.content}")
            if (structured) {
                assertEquals(AiValue.Object(mapOf(
                    "jacket_pattern" to AiValue.Text("striped"),
                    "holding_flowers" to AiValue.Boolean(true),
                )), result.structuredOutput)
            } else {
                val content = result.content.lowercase()
                assertTrue("strip" in content && listOf("flower", "bouquet", "blossom").any { it in content }, result.content)
            }
        }
    }

    companion object {
        private const val imageUrl =
            "https://api.nga.gov/iiif/a2e6da57-3cd1-4235-b20e-95dcaefed6c8/full/!800,800/0/default.jpg"
    }
}
