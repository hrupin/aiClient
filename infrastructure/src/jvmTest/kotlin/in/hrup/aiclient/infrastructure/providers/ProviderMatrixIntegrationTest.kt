package `in`.hrup.aiclient.infrastructure.providers

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.providers.deepseek.DeepSeekResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.kimi.KimiChatProvider
import `in`.hrup.aiclient.infrastructure.providers.openai.OpenAiResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.perplexity.PerplexityAgentProvider
import `in`.hrup.aiclient.infrastructure.providers.xai.XaiResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.zai.ZaiChatProvider
import `in`.hrup.aiclient.provider.StreamingAiProvider
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test

@RunWith(Parameterized::class)
class ProviderMatrixIntegrationTest(
    private val provider: String,
    private val model: String,
) {
    @Test
    fun bufferedWithoutSchema() = runBlocking {
        generateLive(provider, model, createProvider = ::createProvider)
    }

    @Test
    fun bufferedWithSchema() = runBlocking {
        generateLive(provider, model, arithmeticFormat(), createProvider = ::createProvider)
    }

    @Test
    fun streamingWithoutSchema() = runBlocking {
        generateLive(provider, model, streaming = true, createProvider = ::createProvider)
    }

    @Test
    fun streamingWithSchema() = runBlocking {
        generateLive(provider, model, arithmeticFormat(), streaming = true, createProvider = ::createProvider)
    }

    private fun createProvider(transport: HttpTransport, key: String): StreamingAiProvider = when (provider) {
        "OPENAI" -> OpenAiResponsesProvider(transport, key)
        "DEEPSEEK" -> DeepSeekResponsesProvider(transport, key)
        "XAI" -> XaiResponsesProvider(transport, key)
        "ZAI" -> ZaiChatProvider(transport, key)
        "KIMI" -> KimiChatProvider(transport, key)
        "PERPLEXITY" -> PerplexityAgentProvider(transport, key)
        else -> error("Unknown provider: $provider")
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun providers(): List<Array<String>> = listOf(
            arrayOf("OPENAI", "gpt-6-luna"),
            arrayOf("DEEPSEEK", "deepseek-flash"),
            arrayOf("XAI", "grok-4.7"),
            arrayOf("ZAI", "glm-5.3"),
            arrayOf("KIMI", "kimi-k3.1-pro"),
            arrayOf("PERPLEXITY", "perplexity/sonar"),
        )
    }
}
