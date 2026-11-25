package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.infrastructure.structured.toJson
import `in`.hrup.aiclient.model.conversation.AiToolCall
import `in`.hrup.aiclient.model.conversation.AiToolResult
import `in`.hrup.aiclient.model.generation.JsonResponseFormat
import `in`.hrup.aiclient.model.tool.AiTool
import `in`.hrup.aiclient.model.value.AiValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class McpToolRoute(
    val server: Any,
    val definition: AiTool,
    val requireAllowed: (String) -> Unit,
    val serverId: String? = null,
    val invoke: suspend (String, AiValue.Object) -> AiValue.Object,
)

internal fun List<McpAvailableTool>.toRoutes(options: McpToolOptions): List<McpToolRoute> = map { (client, definition) ->
    McpToolRoute(client, definition, client::requireAllowed, client.serverId) { name, arguments ->
        client.callTool(name, arguments, options.maxToolResultBytes)
    }
}

internal class McpToolExecutor(routes: List<McpToolRoute>, private val options: McpToolOptions) {
    private val byName: Map<String, McpToolRoute>
    private val validators = mutableMapOf<String, JsonStructuredOutput>()
    val definitions: List<AiTool>

    init {
        val unique = routes.distinctBy { it.server to it.definition.name }
        if (unique.size != routes.size) throw McpException("Duplicate MCP tool definition on the same server")
        unique.forEach { it.requireAllowed(it.definition.name) }
        val counts = unique.groupingBy { it.definition.name }.eachCount()
        val reserved = counts.keys.toMutableSet()
        val servers = unique.map { it.server }.distinct().withIndex().associate { it.value to it.index + 1 }
        val nativeName = Regex("[a-zA-Z0-9_-]{1,64}")
        val disallowed = Regex("[^a-zA-Z0-9_-]")
        byName = unique.mapIndexed { index, route ->
            val name = route.definition.name
            if (counts[name] == 1 && name.matches(nativeName)) {
                name to route
            } else {
                val safeName = name.take(24).replace(disallowed, "_")
                val server = route.serverId?.take(12)?.replace(disallowed, "_") ?: servers.getValue(route.server).toString()
                val base = "mcp_${server}_${index + 1}_$safeName"
                var alias = base
                var suffix = 1
                while (!reserved.add(alias)) alias = "${base}_${suffix++}"
                alias to route
            }
        }.toMap()
        definitions = byName.map { (name, route) ->
            val original = route.definition
            val serverId = route.serverId
            if (name == original.name || serverId == null) original.copy(name = name)
            else original.copy(name = name, description = "MCP server: $serverId\n${original.description}")
        }
    }

    suspend fun execute(calls: List<AiToolCall>, onResult: (AiToolResult) -> Unit) {
        val validated = calls.map { call ->
            val route = byName[call.name] ?: throw McpException("Model selected an unavailable MCP tool")
            route.requireAllowed(route.definition.name)
            val validator = validators.getOrPut(call.name) {
                JsonStructuredOutput(JsonResponseFormat("mcp_tool_arguments", route.definition.inputSchema))
            }
            route to validator.validate(call.arguments.toJson().toString())
        }
        for ((index, invocation) in validated.withIndex()) {
            currentCoroutineContext().ensureActive()
            val (route, arguments) = invocation
            route.requireAllowed(route.definition.name)
            val result = options.timed("MCP tool call") { route.invoke(route.definition.name, arguments) }
            McpSizeBudget(options.maxToolResultBytes, "MCP tool result").value(result)
            when (result.fields["isError"]) {
                AiValue.Boolean(true) -> throw McpException("MCP tool reported failure")
                null, AiValue.Boolean(false) -> Unit
                else -> throw McpException("Invalid MCP tool error flag")
            }
            onResult(AiToolResult(calls[index].id, structuredContent = result))
        }
    }
}
