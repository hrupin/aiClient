package `in`.hrup.aiclient.infrastructure.providers.zai

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.providers.text.ChatCompletionsText
import `in`.hrup.aiclient.infrastructure.providers.text.TextProviderHttp
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.provider.AiProvider

class ZaiChatProvider(
    transport: HttpTransport,
    apiKey: String,
    endpoint: String = "https://api.z.ai/api/paas/v4/chat/completions",
) : AiProvider {
    private val chat = ChatCompletionsText(TextProviderHttp("Z.ai", transport, endpoint, apiKey))

    override suspend fun generate(request: AiRequest): AiResponse = chat.generate(request)
}
