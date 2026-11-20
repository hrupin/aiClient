package `in`.hrup.aiclient.infrastructure.providers.openai

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.http.createHttpClient
import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAiRemoteMcpIntegrationTest {
    private val serverLabel = "agentgpt"
    private val toolName = "get_available_models"
    private val schema = """
        {
          "type": "object",
          "properties": {
            "models": {
              "type": "array",
              "minItems": 1,
              "items": {
                "type": "object",
                "properties": {
                  "id": {"type": "string"},
                  "name": {"type": "string"},
                  "provider": {"type": "string"}
                },
                "required": ["id", "name", "provider"],
                "additionalProperties": false
              }
            }
          },
          "required": ["models"],
          "additionalProperties": false
        }
    """.trimIndent()

    @Test
    fun callsRemoteToolAndReturnsStructuredModels() = runBlocking {
        val (apiKey, model) = loadOpenAiLiveSettings()
        val validation = JsonStructuredOutput(parseJsonResponseFormat("available_models", schema))
        val payload = request(model, validation.definition)
        createHttpClient().use { client ->
            val response = withTimeout(90_000) {
                HttpTransport(client).execute("https://api.openai.com/v1/responses") {
                    method = HttpMethod.Post
                    bearerAuth(apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(payload.toString())
                }
            }
            val body = response.body.decodeToString()
            assertTrue(response.head.status.value in 200..299, "OpenAI HTTP ${response.head.status.value}: $body")
            val result = Json.parseToJsonElement(body).jsonObject
            assertEquals("completed", result["status"]?.jsonPrimitive?.content, result["error"].toString())
            val items = result.getValue("output").jsonArray.map { it.jsonObject }
            val call = assertNotNull(items.firstOrNull {
                it["type"]?.jsonPrimitive?.content == "mcp_call" &&
                    it["name"]?.jsonPrimitive?.content == toolName &&
                    it["server_label"]?.jsonPrimitive?.content == serverLabel
            }, "OpenAI did not call $toolName")
            assertTrue(call["error"] == null || call["error"] == JsonNull, "MCP call failed: ${call["error"]}")
            assertTrue(!call["output"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content.isNullOrBlank())
            val text = items.filter { it["type"]?.jsonPrimitive?.content == "message" }
                .flatMap { it.getValue("content").jsonArray }
                .map { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.content == "output_text" }
                .joinToString("") { it.getValue("text").jsonPrimitive.content }
            validation.validate(text)
            println(text)
        }
    }

    private fun request(model: String, schema: JsonObject): JsonObject = buildJsonObject {
        put("model", model)
        put("store", false)
        put("input", "Call $toolName once. Return every model from the tool in the same order. " +
            "Copy the exact model ID, display name, and provider into id, name, and provider. Do not invent values.")
        putJsonArray("tools") {
            add(buildJsonObject {
                put("type", "mcp")
                put("server_label", serverLabel)
                put("server_url", "https://agpt.hrup.in/mcp")
                put("require_approval", "never")
                putJsonArray("allowed_tools") { add(toolName) }
            })
        }
        putJsonObject("tool_choice") {
            put("type", "mcp")
            put("server_label", serverLabel)
            put("name", toolName)
        }
        putJsonObject("text") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("name", "available_models")
                put("strict", true)
                put("schema", schema)
            }
        }
    }
}
