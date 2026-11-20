package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.model.value.AiValue
import `in`.hrup.aiclient.provider.AiProvider
import `in`.hrup.aiclient.provider.StreamingAiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** An application-selected tool invocation. The client enforces its tool allowlist. */
data class McpToolInvocation(
    val client: StreamableHttpMcpClient,
    val name: String,
    val arguments: AiValue.Object = AiValue.Object(emptyMap()),
) {
    init {
        require(name.isNotBlank()) { "MCP tool name must not be blank" }
    }
}

/** Invokes the tools in order and gives their results to the provider in one generation request. */
suspend fun AiProvider.generateWithMcp(request: AiRequest, tools: List<McpToolInvocation>): AiResponse =
    generate(request.withMcpResults(tools))

/** Each collection invokes the tools, then streams the provider's final answer. */
fun StreamingAiProvider.streamWithMcp(request: AiRequest, tools: List<McpToolInvocation>): Flow<AiStreamEvent> {
    val invocations = tools.toList()
    return flow {
        emitAll(stream(request.withMcpResults(invocations)))
    }
}

private suspend fun AiRequest.withMcpResults(tools: List<McpToolInvocation>): AiRequest {
    var enriched = this
    for (tool in tools) {
        val result = tool.client.callTool(tool.name, tool.arguments)
        enriched = enriched.withMcpContext(tool.name, result)
    }
    return enriched
}
