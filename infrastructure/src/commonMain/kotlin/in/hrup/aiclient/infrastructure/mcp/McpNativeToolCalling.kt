package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.model.conversation.AiConversationItem
import `in`.hrup.aiclient.model.conversation.AiToolCall
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.JsonResponseFormat

internal class McpNativeToolCalling(
    private val context: McpConversationContext,
    private val turns: McpModelTurns,
    private val finalFormat: JsonResponseFormat?,
    supportsCombinedFormat: Boolean,
) {
    private val roundFormat = finalFormat.takeIf { supportsCombinedFormat }

    suspend fun next(history: List<AiConversationItem>): AiResponse {
        val request = context.nativeRequest(history, roundFormat)
        val round = turns.buffered(request)
        if (round.response.output.any { it is AiToolCall }) return round.response
        if (finalFormat == null || roundFormat != null) return turns.publish(round, request)

        val finalRequest = context.nativeRequest(history, finalFormat, withTools = false)
        return turns.final(finalRequest).copyWithEarlierUsage(round.response.usage)
    }
}
