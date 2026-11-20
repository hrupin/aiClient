package `in`.hrup.aiclient.infrastructure.providers

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.http.createHttpClient
import `in`.hrup.aiclient.infrastructure.mcp.McpAvailableTool
import `in`.hrup.aiclient.infrastructure.mcp.generateWithMcpTools
import `in`.hrup.aiclient.infrastructure.mcp.streamWithMcpTools
import `in`.hrup.aiclient.infrastructure.providers.deepseek.DeepSeekResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.kimi.KimiChatProvider
import `in`.hrup.aiclient.infrastructure.providers.openai.loadOpenAiLiveSettings
import `in`.hrup.aiclient.infrastructure.providers.perplexity.PerplexityAgentProvider
import `in`.hrup.aiclient.infrastructure.providers.xai.XaiResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.zai.ZaiChatProvider
import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.model.generation.JsonResponseFormat
import `in`.hrup.aiclient.model.value.AiValue
import `in`.hrup.aiclient.provider.StreamingAiProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeepSeekLiveIntegrationTest {
    @Test
    fun streamsText() = runBlocking {
        generateLive("DEEPSEEK", "deepseek-flash", streaming = true) { transport, key ->
            DeepSeekResponsesProvider(transport, key)
        }
    }

    @Test
    fun returnsStructuredOutput() = runBlocking {
        generateLive("DEEPSEEK", "deepseek-flash", arithmeticFormat()) { transport, key ->
            DeepSeekResponsesProvider(transport, key)
        }
    }
}

class XaiLiveIntegrationTest {
    @Test
    fun streamsText() = runBlocking {
        generateLive("XAI", "grok-4.7", streaming = true) { transport, key ->
            XaiResponsesProvider(transport, key)
        }
    }

    @Test
    fun returnsStructuredOutput() = runBlocking {
        generateLive("XAI", "grok-4.7", arithmeticFormat()) { transport, key ->
            XaiResponsesProvider(transport, key)
        }
    }
}

class ZaiLiveIntegrationTest {
    @Test
    fun streamsText() = runBlocking {
        generateLive("ZAI", "glm-5.3", streaming = true) { transport, key ->
            ZaiChatProvider(transport, key)
        }
    }

    @Test
    fun returnsStructuredOutput() = runBlocking {
        generateLive("ZAI", "glm-5.3", arithmeticFormat()) { transport, key -> ZaiChatProvider(transport, key) }
    }
}

class KimiLiveIntegrationTest {
    @Test
    fun streamsText() = runBlocking {
        generateLive("KIMI", "kimi-k3.1-pro", streaming = true) { transport, key ->
            KimiChatProvider(transport, key)
        }
    }

    @Test
    fun returnsStructuredOutput() = runBlocking {
        generateLive("KIMI", "kimi-k3.1-pro", arithmeticFormat()) { transport, key -> KimiChatProvider(transport, key) }
    }
}

class PerplexityLiveIntegrationTest {
    @Test
    fun streamsText() = runBlocking {
        generateLive("PERPLEXITY", "perplexity/sonar", streaming = true) { transport, key ->
            PerplexityAgentProvider(transport, key)
        }
    }

    @Test
    fun returnsStructuredOutput() = runBlocking {
        generateLive("PERPLEXITY", "perplexity/sonar", arithmeticFormat()) { transport, key ->
            PerplexityAgentProvider(transport, key)
        }
    }
}

internal fun arithmeticFormat() = parseJsonResponseFormat(
    "result",
    """{"type":"object","properties":{"value":{"type":"integer"}},"required":["value"],"additionalProperties":false}""",
)

internal suspend fun generateLive(
    prefix: String,
    defaultModel: String,
    responseFormat: JsonResponseFormat? = null,
    streaming: Boolean = false,
    exactModel: Boolean = false,
    promptOverride: String? = null,
    mcpTools: List<McpAvailableTool> = emptyList(),
    expectedStructured: AiValue.Object? = AiValue.Object(mapOf("value" to AiValue.Number("42"))),
    expectedTextFragments: List<String> = emptyList(),
    createProvider: (HttpTransport, String) -> StreamingAiProvider,
) {
    val env = readLiveEnv()
    val keyName = "${prefix}_API_KEY"
    val modelName = "${prefix}_MODEL"
    val (apiKey, model) = if (prefix == "OPENAI" && !exactModel) {
        val (key, openAiModel) = loadOpenAiLiveSettings()
        val modelOverride = liveSetting(System.getProperty("aiModel"), System.getenv(modelName), env[modelName])
        key to (modelOverride ?: openAiModel)
    } else {
        val key = checkNotNull(liveSetting(System.getenv(keyName), env[keyName])) {
            "Set $keyName in .env or in the environment before running this test"
        }
        val modelOverride = if (exactModel) null
            else liveSetting(System.getProperty("aiModel"), System.getenv(modelName), env[modelName])
        key to (modelOverride ?: defaultModel)
    }
    val prompt = if (responseFormat == null) "What is 6 times 7? Answer in one short sentence."
        else "Calculate 6 times 7. Return only a JSON object with one property named \"value\", set to the resulting integer. The root value must be an object, never an array."
    val request = AiRequest(
        model = AiModel(model),
        input = listOf(AiMessage(AiRole.USER, listOf(AiContentPart.Text(promptOverride ?: prompt)))),
        responseFormat = responseFormat,
    )

    createHttpClient().use { client ->
        val provider = createProvider(HttpTransport(client), apiKey)
        val response = withTimeout(90_000) {
            if (streaming) {
                val events = provider.streamWithMcpTools(request, mcpTools).toList()
                val completed = assertIs<AiStreamEvent.Completed>(events.last()).response
                val deltas = events.filterIsInstance<AiStreamEvent.TextDelta>()
                assertTrue(deltas.isNotEmpty(), "Expected streamed text from $prefix")
                assertEquals(
                    completed.output.filterIsInstance<AiMessage>().flatMap { it.content }
                        .filterIsInstance<AiContentPart.Text>().joinToString("") { it.text },
                    deltas.joinToString("") { it.text },
                )
                completed
            } else {
                provider.generateWithMcpTools(request, mcpTools)
            }
        }
        val text = response.output.filterIsInstance<AiMessage>().joinToString("\n") { message ->
            message.content.filterIsInstance<AiContentPart.Text>().joinToString("") { it.text }
        }
        println(text)
        assertTrue(text.isNotBlank(), "Expected a non-empty response from $prefix")
        expectedTextFragments.forEach { assertTrue(it in text, "Expected $prefix to preserve $it") }
        if (responseFormat != null) {
            val structured = assertIs<AiValue.Object>(response.structuredOutput)
            expectedStructured?.let { assertEquals(it, structured) }
        }
    }
}
