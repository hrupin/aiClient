package `in`.hrup.aiclient.model.generation

import `in`.hrup.aiclient.model.conversation.AiConversationItem
import `in`.hrup.aiclient.model.value.AiValue

data class AiResponse(
    val output: List<AiConversationItem>,
    val usage: AiUsage? = null,
    val structuredOutput: AiValue.Object? = null,
)

data class AiUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
)
