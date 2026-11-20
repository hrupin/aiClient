package `in`.hrup.aiclient.model.conversation

import `in`.hrup.aiclient.model.value.AiValue

data class AiToolCall(
    val id: String,
    val name: String,
    val arguments: AiValue.Object,
) : AiConversationItem

data class AiToolResult(
    val callId: String,
    val content: List<AiContentPart> = emptyList(),
    val structuredContent: AiValue? = null,
    val isError: Boolean = false,
) : AiConversationItem {
    val role: AiRole get() = AiRole.TOOL
}
