package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.McpServerConfig
import `in`.hrup.aiclient.infrastructure.http.HttpTransport

internal class McpToolDiscovery(
    private val transport: HttpTransport,
    private val servers: Map<String, McpServerConfig>,
    private val options: McpToolOptions,
) {
    suspend fun discover(selection: Map<String, Set<String>>): List<McpAvailableTool> {
        val selected = selection.filterValues { it.isNotEmpty() }.map { (id, names) ->
            val server = requireNotNull(servers[id]) { "Unknown MCP server: $id" }
            SelectedServer(id, names.toSet(), server)
        }
        return selected.flatMap { (id, names, server) ->
            val client = StreamableHttpMcpClient(transport, server.endpoint, names, server.headers, id)
            val available = options.timed("MCP tool discovery") { client.listTools(options.maxContextBytes) }
            val missing = names - available.mapTo(mutableSetOf()) { it.name }
            if (missing.isNotEmpty()) throw McpException("MCP server $id did not list ${missing.size} requested tool(s)")
            available.map { McpAvailableTool(client, it) }
        }
    }

    private data class SelectedServer(val id: String, val names: Set<String>, val config: McpServerConfig)
}
