package `in`.hrup.aiclient.application.mcp

import `in`.hrup.aiclient.model.conversation.AiConversationItem
import `in`.hrup.aiclient.model.conversation.AiToolCall
import `in`.hrup.aiclient.model.conversation.AiToolResult
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiUsage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class McpToolStrategy {
    AUTO,
    NATIVE_TOOL_CALLING,
    ITERATIVE_JSON,
}

data class McpToolOptions(
    val strategy: McpToolStrategy = McpToolStrategy.AUTO,
    val maxToolIterations: Int = 8,
    val maxToolCalls: Int = 32,
    val requestTimeoutMillis: Long = 120_000,
    val maxToolResultBytes: Int = 256 * 1024,
    val maxContextBytes: Int = 2 * 1024 * 1024,
) {
    init {
        require(maxToolIterations >= 0 && maxToolCalls >= 0) { "Tool limits must be nonnegative" }
        require(requestTimeoutMillis > 0) { "MCP request timeout must be positive" }
        require(maxToolResultBytes > 0 && maxContextBytes > 0) { "MCP size limits must be positive" }
    }
}

/** A round limit counts executed batches. The model may still finish after the last allowed batch. */
class McpToolLoop(private val options: McpToolOptions) {
    suspend fun run(
        initialInput: List<AiConversationItem>,
        next: suspend (List<AiConversationItem>) -> AiResponse,
        execute: suspend (List<AiToolCall>, (AiToolResult) -> Unit) -> Unit,
        checkContext: (List<AiConversationItem>) -> Unit,
        fail: (String) -> Nothing,
    ): AiResponse {
        val callIds = initialInput.filterIsInstance<AiToolCall>().mapTo(mutableSetOf()) { it.id }
        var history = initialInput
        var usage: AiUsage? = null
        var iterations = 0
        var toolCalls = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            checkContext(history)
            val round = next(history)
            usage = usage.mergeToolUsage(round.usage)
            val calls = round.output.filterIsInstance<AiToolCall>()
            if (calls.isEmpty()) return if (usage == round.usage) round else round.copy(usage = usage)

            if (iterations >= options.maxToolIterations) fail("MCP tool iteration limit reached")
            if (calls.size > options.maxToolCalls - toolCalls) fail("MCP tool call limit reached")
            if (calls.any { it.id.isBlank() || !callIds.add(it.id) }) fail("Invalid or repeated MCP tool call ID")

            val requested = history + round.output
            checkContext(requested)
            val results = mutableListOf<AiToolResult>()
            execute(calls) { result ->
                results.add(result)
                checkContext(requested + results)
            }
            history = requested + results
            iterations++
            toolCalls += calls.size
        }
    }
}

fun AiUsage?.mergeToolUsage(other: AiUsage?): AiUsage? {
    if (this == null) return other
    if (other == null) return this
    fun sum(a: Long?, b: Long?): Long? = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
    fun total(usage: AiUsage): Long? =
        usage.totalTokens ?: usage.inputTokens?.let { input -> usage.outputTokens?.let { input + it } }
    return AiUsage(
        sum(inputTokens, other.inputTokens),
        sum(outputTokens, other.outputTokens),
        sum(total(this), total(other)),
    )
}
