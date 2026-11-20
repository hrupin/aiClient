package `in`.hrup.aiclient.infrastructure.providers.kimi

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.providers.text.ChatCompletionsText
import `in`.hrup.aiclient.infrastructure.providers.text.TextProviderHttp
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.provider.StreamingAiProvider
import kotlinx.coroutines.flow.Flow

class KimiChatProvider(
    transport: HttpTransport,
    apiKey: String,
    endpoint: String = "https://api.moonshot.ai/v1/chat/completions",
) : StreamingAiProvider {
    private val chat = ChatCompletionsText(TextProviderHttp("Kimi", transport, endpoint, apiKey))

    override suspend fun generate(request: AiRequest): AiResponse = chat.generate(request)

    override fun stream(request: AiRequest): Flow<AiStreamEvent> = chat.stream(request)
}
