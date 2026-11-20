package `in`.hrup.aiclient.model.generation

import `in`.hrup.aiclient.model.conversation.AiConversationItem
import `in`.hrup.aiclient.model.tool.AiTool

data class AiRequest(
    val model: AiModel,
    val input: List<AiConversationItem>,
    val tools: List<AiTool> = emptyList(),
    val responseFormat: JsonResponseFormat? = null,
)
