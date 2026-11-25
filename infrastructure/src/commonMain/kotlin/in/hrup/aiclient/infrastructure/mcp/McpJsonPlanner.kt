package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import `in`.hrup.aiclient.infrastructure.structured.toAiObject
import `in`.hrup.aiclient.model.conversation.AiConversationItem
import `in`.hrup.aiclient.model.conversation.AiToolCall
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.value.AiValue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class McpJsonPlanner(
    private val context: McpConversationContext,
    private val turns: McpModelTurns,
) {
    private var iteration = 0

    suspend fun next(history: List<AiConversationItem>): AiResponse {
        val selection = turns.plan(context.plannerRequest(history, selectionFormat))
        val plan = calls(selection)
        if (plan.isNotEmpty()) return AiResponse(plan, selection.usage)

        return turns.final(context.finalRequest(history)).copyWithEarlierUsage(selection.usage)
    }

    private fun calls(response: AiResponse): List<AiToolCall> {
        val calls = response.structuredOutput?.fields?.get("calls") as? AiValue.Array
            ?: throw McpException("Model did not return an MCP calls array")
        iteration++
        return calls.values.mapIndexed { index, value ->
            val record = (value as? AiValue.Object)?.fields
                ?: throw McpException("MCP tool selection contains an invalid call")
            val name = (record["name"] as? AiValue.Text)?.value
                ?: throw McpException("MCP tool selection is missing a tool name")
            val arguments = (record["arguments"] as? AiValue.Text)?.value
                ?: throw McpException("MCP tool selection is missing arguments")
            val parsed = try {
                Json.parseToJsonElement(arguments) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: throw McpException("MCP tool arguments must be a JSON object")
            AiToolCall("mcp_json_${iteration}_$index", name, parsed.toAiObject(maxDepth = 64))
        }
    }

    private companion object {
        val selectionFormat = parseJsonResponseFormat(
            "mcp_tool_selection",
            """
            {
              "type": "object",
              "properties": {
                "calls": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": { "type": "string" },
                      "arguments": { "type": "string" }
                    },
                    "required": ["name", "arguments"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["calls"],
              "additionalProperties": false
            }
            """.trimIndent(),
        )
    }
}
