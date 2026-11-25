package `in`.hrup.aiclient.provider

import `in`.hrup.aiclient.model.generation.AiModel

data class AiToolCapabilities(
    val structuredOutput: Boolean = false,
    val nativeToolCalling: Boolean = false,
    val streamingToolCalling: Boolean = false,
    val toolsWithStructuredOutput: Boolean = false,
)

/** Optional model capabilities for applications that compose several provider turns. */
interface AiToolCapableProvider : AiProvider {
    fun toolCapabilities(model: AiModel): AiToolCapabilities

    /**
     * Creates a provider scoped to one tool conversation when the adapter needs to retain
     * provider-specific continuation data. A streaming provider must return a streaming session.
     */
    fun openToolSession(maxContextBytes: Int): AiProvider = this
}
