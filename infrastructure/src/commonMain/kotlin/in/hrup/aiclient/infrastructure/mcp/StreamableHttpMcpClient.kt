package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.infrastructure.http.HttpStreamPart
import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.http.sse.SseDecoder
import `in`.hrup.aiclient.infrastructure.structured.toAiObject
import `in`.hrup.aiclient.infrastructure.structured.toJson
import `in`.hrup.aiclient.model.tool.AiTool
import `in`.hrup.aiclient.model.value.AiValue
import io.ktor.client.plugins.retry
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class McpException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Each operation opens its own session. The caller owns the underlying HTTP client. */
class StreamableHttpMcpClient internal constructor(
    private val transport: HttpTransport,
    private val endpoint: String,
    allowedTools: Set<String>,
    headers: Map<String, String>,
    internal val serverId: String?,
) {
    constructor(
        transport: HttpTransport,
        endpoint: String,
        allowedTools: Set<String>,
        headers: Map<String, String> = emptyMap(),
    ) : this(transport, endpoint, allowedTools, headers, null)

    private val requestHeaders = headers.toMap()
    private val permittedTools = allowedTools.toSet()

    init {
        require(headers.keys.none {
            it.lowercase() in setOf("accept", "content-type", "mcp-session-id", "mcp-protocol-version")
        }) { "MCP protocol headers are managed by the client" }
    }

    suspend fun listTools(): List<AiTool> = McpToolOptions().let { options ->
        options.timed("MCP tool discovery") { listTools(options.maxContextBytes) }
    }

    internal suspend fun listTools(maxResponseBytes: Int): List<AiTool> =
        session(maxOf(maxResponseBytes, 16 * 1024)) {
            val tools = mutableListOf<AiTool>()
            val seen = mutableSetOf<String>()
            val budget = McpSizeBudget(maxResponseBytes, "MCP tool catalog")
            var cursor: String? = null
            do {
                val params = cursor?.let { buildJsonObject { put("cursor", it) } }
                val page = rpc("tools/list", params, maxResponseBytes)
                val entries = page["tools"] as? JsonArray ?: throw McpException("Missing MCP tools list")
                for (entry in entries) {
                    val tool = entry as? JsonObject ?: throw McpException("Invalid MCP tool")
                    val name = tool.string("name") ?: throw McpException("Missing MCP tool name")
                    if (name !in permittedTools) continue
                    val schema = tool["inputSchema"] as? JsonObject ?: throw McpException("Missing MCP tool input schema")
                    val definition = AiTool(name, tool.string("description").orEmpty(), schema.toMcpObject())
                    budget.text(definition.name)
                    budget.text(definition.description)
                    budget.value(definition.inputSchema)
                    tools += definition
                }
                cursor = page.string("nextCursor")
                if (cursor != null) {
                    budget.spend(1)
                    budget.text(cursor)
                    if (!seen.add(cursor)) throw McpException("MCP server repeated a pagination cursor")
                }
            } while (cursor != null)
            tools
        }

    suspend fun callTool(name: String, arguments: AiValue.Object = AiValue.Object(emptyMap())): AiValue.Object =
        McpToolOptions().let { options ->
            options.timed("MCP tool call") { callTool(name, arguments, options.maxToolResultBytes) }
        }

    internal suspend fun callTool(name: String, arguments: AiValue.Object, maxResultBytes: Int): AiValue.Object {
        requireAllowed(name)
        return session(maxOf(maxResultBytes, 16 * 1024)) {
            val result = rpc("tools/call", buildJsonObject {
                put("name", name)
                put("arguments", arguments.toJson())
            }, maxResultBytes)
            when (result["isError"]) {
                JsonPrimitive(true) -> throw McpException("MCP tool reported failure")
                null, JsonPrimitive(false) -> result.toMcpObject()
                else -> throw McpException("Invalid MCP tool error flag")
            }
        }
    }

    internal fun requireAllowed(name: String) {
        require(name in permittedTools) { "MCP tool is not allowed" }
    }

    private suspend fun <T> session(maxResponseBytes: Int, action: suspend Session.() -> T): T {
        val session = Session(maxResponseBytes)
        var failure: Throwable? = null
        try {
            session.initialize()
            return session.action()
        } catch (cause: Throwable) {
            failure = cause.toPublicMcpFailure("MCP session request failed")
            throw failure
        } finally {
            try {
                withContext(NonCancellable) {
                    withTimeoutOrNull(5_000) { session.close() }
                        ?: throw McpException("MCP session cleanup timed out")
                }
            } catch (cause: Exception) {
                val cleanupFailure = cause.toPublicMcpFailure("MCP session cleanup failed")
                if (failure == null) throw cleanupFailure
                failure.addSuppressed(cleanupFailure)
            }
        }
    }

    private inner class Session(private val maxResponseBytes: Int) {
        private var version: String? = null
        private var sessionId: String? = null
        private var sequence = 0

        suspend fun initialize() {
            val initialized = rpc("initialize", buildJsonObject {
                put("protocolVersion", "2025-11-25")
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "aiClient")
                    put("version", "1.0")
                }
            })
            version = initialized.string("protocolVersion")
                ?: throw McpException("MCP initialize response has no protocolVersion")
            if (version !in setOf("2025-11-25", "2025-06-18", "2025-03-26")) {
                throw McpException("Unsupported MCP protocol version")
            }
            val status = status {
                post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            }
            if (status != 202) throw McpException("MCP initialized returned HTTP $status")
            val capabilities = initialized["capabilities"] as? JsonObject
            if (capabilities?.get("tools") !is JsonObject) throw McpException("MCP server does not advertise tools")
        }

        suspend fun close() {
            if (sessionId == null) return
            val status = status {
                method = HttpMethod.Delete
                sessionHeaders()
            }
            if (status !in 200..299 && status != 405 && status != 404) {
                throw McpException("MCP session deletion returned HTTP $status")
            }
        }

        private suspend fun status(configure: HttpRequestBuilder.() -> Unit): Int =
            (transport.stream(endpoint, configure).first() as HttpStreamPart.Head).value.status.value

        suspend fun rpc(
            rpcMethod: String,
            params: JsonObject?,
            maxBytes: Int = maxResponseBytes,
        ): JsonObject {
            val id = JsonPrimitive(++sequence)
            val message = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", rpcMethod)
                params?.let { put("params", it) }
            }
            val decoder = SseDecoder()
            val chunks = mutableListOf<ByteArray>()
            var isSse = false
            val budget = McpSizeBudget(maxBytes, "MCP $rpcMethod response")
            val response = transport.stream(endpoint) {
                post(message.toString())
                if (rpcMethod == "tools/call") retry { noRetry() }
            }.transform { part ->
                when (part) {
                    is HttpStreamPart.Head -> {
                        val head = part.value
                        if (head.status.value != 200) throw McpException("MCP $rpcMethod returned HTTP ${head.status.value}")
                        if (rpcMethod == "initialize") sessionId = head.headers["Mcp-Session-Id"]
                        when (head.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()) {
                            "application/json" -> Unit
                            "text/event-stream" -> isSse = true
                            else -> throw McpException("Unexpected MCP response content type")
                        }
                    }
                    is HttpStreamPart.Chunk -> {
                        budget.spend(part.bytes.size)
                        if (isSse) {
                            decoder.decode(part.bytes) { frame ->
                                if (frame.data.isNotBlank()) {
                                    val reply = parse(frame.data)
                                    if (reply["id"] == id || reply["error"] != null && reply["id"] == JsonNull) emit(reply)
                                }
                            }
                        } else {
                            chunks += part.bytes
                        }
                    }
                }
            }.firstOrNull() ?: run {
                if (isSse) throw McpException("MCP stream ended before the $rpcMethod response")
                val body = ByteArray(chunks.sumOf { it.size })
                var offset = 0
                for (chunk in chunks) {
                    chunk.copyInto(body, offset)
                    offset += chunk.size
                }
                parse(body.decodeToString())
            }
            if (response.string("jsonrpc") != "2.0") throw McpException("Invalid MCP JSON-RPC version")
            response["error"]?.let { throw McpException("MCP $rpcMethod returned a JSON-RPC error") }
            if (response["id"] != id) throw McpException("Mismatched MCP response id")
            return response["result"] as? JsonObject ?: throw McpException("Invalid MCP $rpcMethod result")
        }

        private fun HttpRequestBuilder.post(body: String) {
            method = HttpMethod.Post
            sessionHeaders()
            header(HttpHeaders.ContentType, "application/json")
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody(body)
        }

        private fun HttpRequestBuilder.sessionHeaders() {
            requestHeaders.forEach { (name, value) -> header(name, value) }
            version?.let { header("MCP-Protocol-Version", it) }
            sessionId?.let { header("Mcp-Session-Id", it) }
        }
    }

    private fun parse(body: String): JsonObject = try {
        Json.parseToJsonElement(body) as? JsonObject ?: throw McpException("MCP response must be a JSON object")
    } catch (_: SerializationException) {
        throw McpException("Invalid MCP JSON")
    }

    private fun JsonObject.toMcpObject(): AiValue.Object =
        try {
            toAiObject(maxDepth = 64)
        } catch (_: IllegalArgumentException) {
            throw McpException("Invalid nested MCP JSON data")
        }

    private fun Throwable.toPublicMcpFailure(message: String): Throwable =
        when (this) {
            is McpException, is CancellationException -> this
            is Exception -> McpException(message)
            else -> this
        }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
