package `in`.hrup.aiclient.infrastructure.providers.openai

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.http.createHttpClient
import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.value.AiValue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenAiLiveIntegrationTest {
    @Test
    fun returnsValidatedStructuredOutput() = runBlocking {
        val (apiKey, model) = loadOpenAiLiveSettings()
        val format = parseJsonResponseFormat(
            "structured_result",
            """
            {
              "type": "object",
              "properties": {
                "title": {"type": "string"},
                "tags": {"type": "array", "items": {"type": "string"}},
                "summary": {
                  "type": "object",
                  "properties": {
                    "name": {"type": "string"},
                    "value": {"type": "integer"},
                    "verified": {"type": "boolean"}
                  },
                  "required": ["name", "value", "verified"],
                  "additionalProperties": false
                }
              },
              "required": ["title", "tags", "summary"],
              "additionalProperties": false
            }
            """.trimIndent(),
        )
        val request = AiRequest(
            model = AiModel(model),
            input = listOf(AiMessage(AiRole.USER, listOf(AiContentPart.Text(
                "Set title to 'Arithmetic', tags to ['math', 'example'] in that order, " +
                    "and summary name to 'Multiplication', value to the result of 6 times 7, and verified to true.",
            )))),
            responseFormat = format,
        )
        createHttpClient().use { client ->
            val provider = OpenAiResponsesProvider(HttpTransport(client), apiKey)
            val response = withTimeout(60_000) { provider.generate(request) }
            val message = assertIs<AiMessage>(response.output.single())
            val json = message.content.joinToString("") { assertIs<AiContentPart.Text>(it).text }
            println(json)
            val expected = AiValue.Object(mapOf(
                "title" to AiValue.Text("Arithmetic"),
                "tags" to AiValue.Array(listOf(AiValue.Text("math"), AiValue.Text("example"))),
                "summary" to AiValue.Object(mapOf(
                    "name" to AiValue.Text("Multiplication"),
                    "value" to AiValue.Number("42"),
                    "verified" to AiValue.Boolean(true),
                )),
            ))
            assertEquals(expected, response.structuredOutput)
        }
    }
}
