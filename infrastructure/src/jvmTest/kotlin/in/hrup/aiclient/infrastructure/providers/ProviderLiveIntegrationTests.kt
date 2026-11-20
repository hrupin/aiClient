package `in`.hrup.aiclient.infrastructure.providers

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.http.createHttpClient
import `in`.hrup.aiclient.infrastructure.providers.deepseek.DeepSeekResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.kimi.KimiChatProvider
import `in`.hrup.aiclient.infrastructure.providers.perplexity.PerplexityAgentProvider
import `in`.hrup.aiclient.infrastructure.providers.xai.XaiResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.zai.ZaiChatProvider
import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.JsonResponseFormat
import `in`.hrup.aiclient.model.value.AiValue
import `in`.hrup.aiclient.provider.AiProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeepSeekLiveIntegrationTest {
    @Test
    fun returnsStructuredOutput() = runBlocking {
        generateLive("DEEPSEEK", "deepseek-flash", arithmeticFormat()) { transport, key ->
            DeepSeekResponsesProvider(transport, key)
        }
    }
}

class XaiLiveIntegrationTest {
    @Test
    fun returnsStructuredOutput() = runBlocking {
        generateLive("XAI", "grok-4.7", arithmeticFormat()) { transport, key ->
            XaiResponsesProvider(transport, key)
        }
    }
}

class ZaiLiveIntegrationTest {
    @Test
    fun returnsStructuredOutput() = runBlocking {
        generateLive("ZAI", "glm-5.3", arithmeticFormat()) { transport, key -> ZaiChatProvider(transport, key) }
    }
}

class KimiLiveIntegrationTest {
    @Test
    fun returnsStructuredOutput() = runBlocking {
        generateLive("KIMI", "kimi-k3.1-pro", arithmeticFormat()) { transport, key -> KimiChatProvider(transport, key) }
    }
}

class PerplexityLiveIntegrationTest {
    @Test
    fun returnsStructuredOutput() = runBlocking {
        generateLive("PERPLEXITY", "perplexity/sonar", arithmeticFormat()) { transport, key ->
            PerplexityAgentProvider(transport, key)
        }
    }
}

private fun arithmeticFormat() = parseJsonResponseFormat(
    "result",
    """{"type":"object","properties":{"value":{"type":"integer"}},"required":["value"],"additionalProperties":false}""",
)

private suspend fun generateLive(
    prefix: String,
    defaultModel: String,
    responseFormat: JsonResponseFormat? = null,
    createProvider: (HttpTransport, String) -> AiProvider,
) {
    val env = readLiveEnv()
    val keyName = "${prefix}_API_KEY"
    val apiKey = checkNotNull(liveSetting(System.getenv(keyName), env[keyName])) {
        "Set $keyName in .env or in the environment before running this test"
    }
    val modelName = "${prefix}_MODEL"
    val model = liveSetting(System.getProperty("aiModel"), System.getenv(modelName), env[modelName]) ?: defaultModel
    val prompt = if (responseFormat == null) "What is 6 times 7? Answer in one short sentence."
        else "What is 6 times 7? Return a JSON object with the answer as the integer value."
    val request = AiRequest(
        model = AiModel(model),
        input = listOf(AiMessage(AiRole.USER, listOf(AiContentPart.Text(prompt)))),
        responseFormat = responseFormat,
    )

    createHttpClient().use { client ->
        val provider = createProvider(HttpTransport(client), apiKey)
        val response = withTimeout(90_000) { provider.generate(request) }
        val text = response.output.filterIsInstance<AiMessage>().joinToString("\n") { message ->
            message.content.filterIsInstance<AiContentPart.Text>().joinToString("") { it.text }
        }
        println(text)
        assertTrue(text.isNotBlank(), "Expected a non-empty response from $prefix")
        if (responseFormat != null) {
            assertEquals(AiValue.Object(mapOf("value" to AiValue.Number("42"))), response.structuredOutput)
        }
    }
}
