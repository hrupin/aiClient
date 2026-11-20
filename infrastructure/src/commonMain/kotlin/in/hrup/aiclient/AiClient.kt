package `in`.hrup.aiclient

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.mcp.McpAvailableTool
import `in`.hrup.aiclient.infrastructure.mcp.StreamableHttpMcpClient
import `in`.hrup.aiclient.infrastructure.mcp.generateWithMcpTools
import `in`.hrup.aiclient.infrastructure.mcp.streamWithMcpTools
import `in`.hrup.aiclient.infrastructure.providers.deepseek.DeepSeekResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.kimi.KimiChatProvider
import `in`.hrup.aiclient.infrastructure.providers.openai.OpenAiResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.perplexity.PerplexityAgentProvider
import `in`.hrup.aiclient.infrastructure.providers.xai.XaiResponsesProvider
import `in`.hrup.aiclient.infrastructure.providers.zai.ZaiChatProvider
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.model.generation.JsonResponseFormat
import `in`.hrup.aiclient.provider.StreamingAiProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * DI entry point. The application owns and closes [httpClient].
 *
 * Each request's `mcpTools` maps configured server IDs to the tool names available
 * to that request. An empty selection makes no MCP requests.
 */
class AiClient(httpClient: HttpClient, private val config: AiClientConfig) {
    private val transport = HttpTransport(httpClient)

    suspend fun generate(
        prompt: String,
        provider: AiProviderId = config.defaultProvider,
        model: String? = null,
        responseFormat: JsonResponseFormat? = null,
        mcpTools: Map<String, Set<String>> = emptyMap(),
    ): AiResponse = generate(textRequest(prompt, provider, model, responseFormat), provider, mcpTools)

    suspend fun generate(
        request: AiRequest,
        provider: AiProviderId = config.defaultProvider,
        mcpTools: Map<String, Set<String>> = emptyMap(),
    ): AiResponse {
        val adapter = adapter(provider)
        return adapter.generateWithMcpTools(request, availableTools(mcpTools))
    }

    fun stream(
        prompt: String,
        provider: AiProviderId = config.defaultProvider,
        model: String? = null,
        responseFormat: JsonResponseFormat? = null,
        mcpTools: Map<String, Set<String>> = emptyMap(),
    ): Flow<AiStreamEvent> = stream(textRequest(prompt, provider, model, responseFormat), provider, mcpTools)

    /** Resolves the provider and discovers its requested tools when the flow is collected. */
    fun stream(
        request: AiRequest,
        provider: AiProviderId = config.defaultProvider,
        mcpTools: Map<String, Set<String>> = emptyMap(),
    ): Flow<AiStreamEvent> {
        val selection = mcpTools.mapValues { (_, names) -> names.toSet() }
        return flow {
            val adapter = adapter(provider)
            emitAll(adapter.streamWithMcpTools(request, availableTools(selection)))
        }
    }

    private fun textRequest(prompt: String, provider: AiProviderId, model: String?, format: JsonResponseFormat?) =
        AiRequest(
            model = AiModel(model ?: config.providers[provider]?.defaultModel ?: provider.defaultModelId),
            input = listOf(AiMessage(AiRole.USER, listOf(AiContentPart.Text(prompt)))),
            responseFormat = format,
        )

    private suspend fun availableTools(selection: Map<String, Set<String>>): List<McpAvailableTool> {
        val clients = selection.filterValues { it.isNotEmpty() }.map { (id, names) ->
            val server = requireNotNull(config.mcpServers[id]) { "Unknown MCP server: $id" }
            StreamableHttpMcpClient(transport, server.endpoint, names, server.headers)
        }
        return clients.flatMap { client ->
            client.listTools().map { McpAvailableTool(client, it) }
        }
    }

    private fun adapter(provider: AiProviderId): StreamingAiProvider {
        val settings = config.providers[provider]
        val key = settings?.apiKey?.takeIf(String::isNotBlank) ?: throw MissingAiApiKeyException(provider)
        val endpoint = settings.endpoint
        return when (provider) {
            AiProviderId.OPENAI -> endpoint?.let { OpenAiResponsesProvider(transport, key, it) }
                ?: OpenAiResponsesProvider(transport, key)
            AiProviderId.DEEPSEEK -> endpoint?.let { DeepSeekResponsesProvider(transport, key, it) }
                ?: DeepSeekResponsesProvider(transport, key)
            AiProviderId.XAI -> endpoint?.let { XaiResponsesProvider(transport, key, it) }
                ?: XaiResponsesProvider(transport, key)
            AiProviderId.ZAI -> endpoint?.let { ZaiChatProvider(transport, key, it) }
                ?: ZaiChatProvider(transport, key)
            AiProviderId.KIMI -> endpoint?.let { KimiChatProvider(transport, key, it) }
                ?: KimiChatProvider(transport, key)
            AiProviderId.PERPLEXITY -> endpoint?.let { PerplexityAgentProvider(transport, key, it) }
                ?: PerplexityAgentProvider(transport, key)
        }
    }
}
