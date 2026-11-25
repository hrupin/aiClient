package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.application.mcp.mergeToolUsage
import `in`.hrup.aiclient.model.conversation.AiToolCall
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.model.generation.AiUsage
import `in`.hrup.aiclient.provider.AiProvider
import `in`.hrup.aiclient.provider.StreamingAiProvider

internal class McpStreamRound(val response: AiResponse, val events: List<AiStreamEvent>)

internal class McpModelTurns(
    private val provider: AiProvider,
    private val options: McpToolOptions,
    private val emit: (suspend (AiStreamEvent) -> Unit)?,
) {
    private val streaming = if (emit != null) {
        provider as? StreamingAiProvider ?: throw McpException("Tool session does not support streaming")
    } else null

    suspend fun plan(request: AiRequest): AiResponse =
        options.timed("MCP tool selection") { provider.generate(request) }

    suspend fun buffered(request: AiRequest): McpStreamRound = options.timed("AI tool generation") {
        if (streaming == null) {
            McpStreamRound(provider.generate(request), emptyList())
        } else {
            val events = mutableListOf<AiStreamEvent>()
            val budget = McpSizeBudget(options.maxContextBytes, "MCP streamed turn")
            val response = streaming.readMcpRound(request) { event ->
                budget.spend(1)
                when (event) {
                    is AiStreamEvent.TextDelta -> budget.text(event.text)
                    is AiStreamEvent.OutputItemCompleted -> budget.item(event.item)
                    is AiStreamEvent.Completed -> error("Terminal events are handled by readMcpRound")
                }
                events.add(event)
            }
            McpStreamRound(response, events)
        }
    }

    suspend fun publish(round: McpStreamRound, request: AiRequest): AiResponse {
        requireFinalResponse(round.response, request)
        if (emit != null) for (event in round.events) emit(event)
        return round.response
    }

    suspend fun final(request: AiRequest): AiResponse = options.timed("AI final generation") {
        val response = if (streaming == null) provider.generate(request) else {
            streaming.readMcpRound(request) { event ->
                if (event is AiStreamEvent.OutputItemCompleted && event.item is AiToolCall) {
                    throw McpException("Model requested a tool during final generation")
                }
                checkNotNull(emit)(event)
            }
        }
        requireFinalResponse(response, request)
        response
    }

    private fun requireFinalResponse(response: AiResponse, request: AiRequest) {
        if (response.output.any { it is AiToolCall }) throw McpException("Model requested a tool during final generation")
        if (request.responseFormat != null && response.structuredOutput == null) {
            throw McpException("Model did not return the requested structured final response")
        }
    }
}

private suspend fun StreamingAiProvider.readMcpRound(
    request: AiRequest,
    onEvent: suspend (AiStreamEvent) -> Unit,
): AiResponse {
    var result: AiResponse? = null
    stream(request).collect { event ->
        if (result != null) throw McpException("AI stream emitted an event after completion")
        if (event is AiStreamEvent.Completed) result = event.response else onEvent(event)
    }
    return result ?: throw McpException("AI stream ended before completion")
}

internal fun AiResponse.copyWithEarlierUsage(earlier: AiUsage?): AiResponse {
    val total = earlier.mergeToolUsage(usage)
    return if (total == usage) this else copy(usage = total)
}
