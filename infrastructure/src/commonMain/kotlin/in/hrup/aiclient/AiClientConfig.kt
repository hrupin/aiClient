package `in`.hrup.aiclient

import kotlinx.serialization.Serializable

@Serializable
enum class AiProviderId(internal val defaultModelId: String, val apiKeySetting: String) {
    OPENAI("gpt-6-luna", "OPENAI_API_KEY"),
    DEEPSEEK("deepseek-flash", "DEEPSEEK_API_KEY"),
    XAI("grok-4.7", "XAI_API_KEY"),
    ZAI("glm-5.3", "ZAI_API_KEY"),
    KIMI("kimi-k3.1-pro", "KIMI_API_KEY"),
    PERPLEXITY("perplexity/sonar", "PERPLEXITY_API_KEY"),
    BFL("flux-3-image", "BFL_API_KEY"),
}

class AiProviderConfig(
    val apiKey: String? = null,
    val defaultModel: String? = null,
    val endpoint: String? = null,
)

/** Connection settings for a named MCP server. */
class McpServerConfig(
    val endpoint: String,
    headers: Map<String, String> = emptyMap(),
) {
    val headers = headers.toMap()
}

class AiClientConfig(
    val defaultProvider: AiProviderId = AiProviderId.OPENAI,
    providers: Map<AiProviderId, AiProviderConfig> = emptyMap(),
    mcpServers: Map<String, McpServerConfig> = emptyMap(),
) {
    val providers = providers.toMap()
    val mcpServers = mcpServers.toMap()
}

class MissingAiApiKeyException(val provider: AiProviderId) :
    IllegalStateException("API key is not configured for ${provider.name}. Set ${provider.apiKeySetting}.")
