package `in`.hrup.aiclient.infrastructure.providers.zai

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.providers.text.ChatCompletionsText
import `in`.hrup.aiclient.infrastructure.providers.text.TextProviderHttp
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.provider.AiToolCapableProvider
import `in`.hrup.aiclient.provider.AiToolCapabilities
import `in`.hrup.aiclient.provider.StreamingAiProvider
import kotlinx.coroutines.flow.Flow

class ZaiChatProvider(
    transport: HttpTransport,
    apiKey: String,
    endpoint: String = "https://api.z.ai/api/paas/v4/chat/completions",
) : StreamingAiProvider, AiToolCapableProvider {
    override fun toolCapabilities(model: AiModel): AiToolCapabilities {
        val native = model.id in toolModels
        return AiToolCapabilities(
            structuredOutput = model.id != "glm-5v-turbo",
            nativeToolCalling = native,
            streamingToolCalling = native,
        )
    }

    private val chat = ChatCompletionsText(
        TextProviderHttp("Z.ai", transport, endpoint, apiKey), supportsToolStreamParameter = true,
    )

    override fun openToolSession(maxContextBytes: Int): StreamingAiProvider {
        val session = chat.toolSession(maxContextBytes)
        return object : StreamingAiProvider {
            override suspend fun generate(request: AiRequest): AiResponse = session.generate(request)
            override fun stream(request: AiRequest): Flow<AiStreamEvent> = session.stream(request)
        }
    }

    override suspend fun generate(request: AiRequest): AiResponse = chat.generate(request)

    override fun stream(request: AiRequest): Flow<AiStreamEvent> = chat.stream(request)
    private companion object {
        val toolModels = setOf(
            "glm-5.3", "glm-5.3-flash", "glm-5.3-flashx", "glm-5.2", "glm-5.1", "glm-5", "glm-4.7", "glm-4.6",
        )
    }
}
