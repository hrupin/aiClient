package `in`.hrup.aiclient.model.generation

import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiConversationItem
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.value.AiValue

data class AiResponse(
    val output: List<AiConversationItem>,
    val usage: AiUsage? = null,
    val structuredOutput: AiValue.Object? = null,
    val provider: String? = null,
    val model: String? = null,
    /** HTTP request body sent to the provider by AiClient. */
    val request: String = "",
    /** HTTP response body received from the provider by AiClient, including SSE for streams. */
    val response: String = "",
) {
    val content: String
        get() = output.filterIsInstance<AiMessage>()
            .filter { it.role == AiRole.ASSISTANT }
            .joinToString("\n") { message ->
                message.content.filterIsInstance<AiContentPart.Text>().joinToString("") { it.text }
            }

    val tokensSent: Long get() = usage?.inputTokens ?: 0L
    val tokensReceived: Long get() = usage?.outputTokens ?: 0L
}

data class AiUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
)
