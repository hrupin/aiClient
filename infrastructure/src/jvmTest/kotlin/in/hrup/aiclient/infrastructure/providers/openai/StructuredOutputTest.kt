package `in`.hrup.aiclient.infrastructure.providers.openai

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.StructuredOutputException
import `in`.hrup.aiclient.model.value.AiValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StructuredOutputTest {
    private val schema = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "required": ["operations", "warnings"],
          "additionalProperties": false,
          "properties": {
            "operations": {"type": "array", "items": {
              "type": "object",
              "required": ["amount"],
              "additionalProperties": false,
              "properties": {"amount": {"type": "string", "pattern": "^[0-9]+\\.[0-9]{2}$"}}
            }},
            "warnings": {"type": "array", "items": {"type": "string"}}
          }
        }
    """.trimIndent()

    private val request = AiRequest(
        model = AiModel("test-model"),
        input = listOf(AiMessage(AiRole.USER, listOf(AiContentPart.Text("Extract the operation")))),
        responseFormat = parseJsonResponseFormat("operations_result", schema),
    )

    @Test
    fun sendsOriginalSchemaAndReturnsValidatedObject() = runBlocking {
        HttpClient(MockEngine { request ->
            val payload = Json.decodeFromString<ResponsesRequest>(
                assertIs<OutgoingContent.ByteArrayContent>(request.body).bytes().decodeToString(),
            )
            val format = requireNotNull(payload.text).format
            assertEquals("json_schema", format.type)
            assertEquals("operations_result", format.name)
            assertTrue(format.strict)
            assertEquals(Json.parseToJsonElement(schema), format.schema)
            respond(completed("""{"operations":[{"amount":"12.50"}],"warnings":[]}"""))
        }).use { client ->
            val provider = OpenAiResponsesProvider(HttpTransport(client), "test-key")
            val output = requireNotNull(provider.generate(request).structuredOutput)
            val operation = assertIs<AiValue.Array>(output.fields["operations"]).values.single()
            assertEquals(AiValue.Object(mapOf("amount" to AiValue.Text("12.50"))), operation)
            assertEquals(AiValue.Array(emptyList()), output.fields["warnings"])
        }
    }

    @Test
    fun rejectsInvalidNestedPatternAndMissingRequiredField() = runBlocking {
        for (text in listOf(
            """{"operations":[{"amount":"12.5"}],"warnings":[]}""",
            """{"operations":[{"amount":"12.50"}]}""",
            """{"operations":[{"amount":"12.50","extra":true}],"warnings":[]}""",
        )) {
            HttpClient(MockEngine { respond(completed(text)) }).use { client ->
                val provider = OpenAiResponsesProvider(HttpTransport(client), "test-key")
                val error = assertFailsWith<StructuredOutputException> { provider.generate(request) }
                assertTrue(error.violations.isNotEmpty())
            }
        }
    }

    @Test
    fun rejectsMalformedJson() = runBlocking {
        HttpClient(MockEngine { respond(completed("{")) }).use { client ->
            val provider = OpenAiResponsesProvider(HttpTransport(client), "test-key")
            assertFailsWith<StructuredOutputException> { provider.generate(request) }
        }
        Unit
    }

    private fun completed(text: String) =
        """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":${JsonPrimitive(text)}}]}]}"""
}
