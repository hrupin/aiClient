package `in`.hrup.aiclient.model.generation

import `in`.hrup.aiclient.model.conversation.AiConversationItem

sealed interface AiStreamEvent {
    /** Indices are zero-based and refer to the final output item and its content part. */
    data class TextDelta(
        val outputIndex: Int,
        val contentIndex: Int,
        val text: String,
    ) : AiStreamEvent

    /** A complete output item, including fully assembled arguments for tool calls. */
    data class OutputItemCompleted(
        val outputIndex: Int,
        val item: AiConversationItem,
    ) : AiStreamEvent

    /** The final response, including usage when available. */
    data class Completed(val response: AiResponse) : AiStreamEvent
}
