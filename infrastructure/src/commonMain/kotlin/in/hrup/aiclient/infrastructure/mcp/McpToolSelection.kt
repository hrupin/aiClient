package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.application.mcp.McpToolLoop
import `in`.hrup.aiclient.model.conversation.AiConversationItem
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.model.tool.AiTool
import `in`.hrup.aiclient.provider.AiProvider
import `in`.hrup.aiclient.provider.AiToolCapableProvider
import `in`.hrup.aiclient.provider.AiToolCapabilities
import `in`.hrup.aiclient.provider.StreamingAiProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

typealias McpToolOptions = `in`.hrup.aiclient.application.mcp.McpToolOptions
typealias McpToolStrategy = `in`.hrup.aiclient.application.mcp.McpToolStrategy

/** An available tool and the client that can invoke it. */
data class McpAvailableTool(val client: StreamableHttpMcpClient, val definition: AiTool)

suspend fun AiProvider.generateWithMcpTools(request: AiRequest, tools: List<McpAvailableTool>): AiResponse =
    generateWithMcpTools(request, tools, McpToolOptions())

suspend fun AiProvider.generateWithMcpTools(
    request: AiRequest,
    tools: List<McpAvailableTool>,
    options: McpToolOptions,
): AiResponse {
    if (tools.isEmpty()) return generate(request)
    return runMcpConversation(request, tools.toRoutes(options), options)
}

fun StreamingAiProvider.streamWithMcpTools(request: AiRequest, tools: List<McpAvailableTool>): Flow<AiStreamEvent> =
    streamWithMcpTools(request, tools, McpToolOptions())

/** Each collection starts a new tool conversation and emits only the final answer's events. */
fun StreamingAiProvider.streamWithMcpTools(
    request: AiRequest,
    tools: List<McpAvailableTool>,
    options: McpToolOptions,
): Flow<AiStreamEvent> {
    val available = tools.toList()
    if (available.isEmpty()) return flow { emitAll(stream(request)) }
    return streamMcpConversation(request, options) { available.toRoutes(options) }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StreamingAiProvider.streamMcpConversation(
    request: AiRequest,
    options: McpToolOptions,
    routes: () -> List<McpToolRoute>,
): Flow<AiStreamEvent> = channelFlow {
    val response = runMcpConversation(request, routes(), options) { send(it) }
    send(AiStreamEvent.Completed(response))
}.buffer(0)

internal suspend fun AiProvider.runMcpConversation(
    request: AiRequest,
    routes: List<McpToolRoute>,
    options: McpToolOptions,
    emit: (suspend (AiStreamEvent) -> Unit)? = null,
): AiResponse {
    require(request.tools.isEmpty()) { "MCP selection requires a request without application-managed tools" }
    val streaming = emit != null
    val capable = this as? AiToolCapableProvider
    // The original extensions used Structured Output for custom providers without capability metadata.
    val capabilities = capable?.toolCapabilities(request.model) ?: AiToolCapabilities(structuredOutput = true)
    val strategy = selectMcpStrategy(options.strategy, capabilities, streaming, request)
    val executor = McpToolExecutor(routes, options)
    val context = McpConversationContext(request, executor.definitions, options)
    val next: suspend (List<AiConversationItem>) -> AiResponse = when (strategy) {
        McpToolStrategy.ITERATIVE_JSON -> McpJsonPlanner(context, McpModelTurns(this, options, emit))::next
        McpToolStrategy.NATIVE_TOOL_CALLING -> {
            val provider = checkNotNull(capable).openToolSession(options.maxContextBytes)
            McpNativeToolCalling(
                context, McpModelTurns(provider, options, emit), request.responseFormat,
                capabilities.toolsWithStructuredOutput,
            )::next
        }
        McpToolStrategy.AUTO -> error("MCP strategy was not resolved")
    }
    return McpToolLoop(options).run(
        initialInput = request.input,
        next = next,
        execute = executor::execute,
        checkContext = context::checkHistory,
        fail = { throw McpException(it) },
    )
}

private fun selectMcpStrategy(
    strategy: McpToolStrategy,
    capabilities: AiToolCapabilities,
    streaming: Boolean,
    request: AiRequest,
): McpToolStrategy {
    val native = capabilities.nativeToolCalling && (!streaming || capabilities.streamingToolCalling) &&
        (request.responseFormat == null || capabilities.structuredOutput || capabilities.toolsWithStructuredOutput)
    return when {
        strategy != McpToolStrategy.ITERATIVE_JSON && native -> McpToolStrategy.NATIVE_TOOL_CALLING
        strategy != McpToolStrategy.NATIVE_TOOL_CALLING && capabilities.structuredOutput -> McpToolStrategy.ITERATIVE_JSON
        else -> throw McpException("Model ${request.model.id} has no MCP execution mode for $strategy and this request")
    }
}

internal suspend fun <T : Any> McpToolOptions.timed(operation: String, action: suspend () -> T): T =
    withTimeoutOrNull(requestTimeoutMillis) { action() } ?: throw McpException("$operation timed out")
