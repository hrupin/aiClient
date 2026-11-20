package `in`.hrup.aiclient.infrastructure.structured

import `in`.hrup.aiclient.model.generation.JsonResponseFormat
import `in`.hrup.aiclient.model.generation.StructuredOutputException
import `in`.hrup.aiclient.model.value.AiValue
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull

fun parseJsonResponseFormat(name: String, definition: String): JsonResponseFormat {
    val schema = Json.parseToJsonElement(definition) as? JsonObject
        ?: throw IllegalArgumentException("Response schema must be a JSON object")
    return JsonResponseFormat(name, schema.toAiObject())
}

internal class JsonStructuredOutput(format: JsonResponseFormat) {
    val definition: JsonObject = JsonObject(format.schema.fields.mapValues { (_, value) -> value.toJson() })
    private val schema = JsonSchema.fromDefinition(definition.toString())

    fun validate(text: String): AiValue.Object {
        val output = try {
            Json.parseToJsonElement(text) as? JsonObject
                ?: throw StructuredOutputException("Structured output must be a JSON object")
        } catch (cause: SerializationException) {
            throw StructuredOutputException("Structured output is invalid JSON", cause = cause)
        }

        val errors = mutableListOf<ValidationError>()
        if (!schema.validate(output, errors::add)) {
            throw StructuredOutputException(
                "Structured output does not match the response schema",
                errors.map { "${it.objectPath}: ${it.message}" },
            )
        }
        return output.toAiObject()
    }
}

internal fun JsonObject.toAiObject(): AiValue.Object =
    AiValue.Object(mapValues { (_, value) -> value.toAiValue() })

private fun JsonElement.toAiValue(): AiValue = when (this) {
    is JsonObject -> toAiObject()
    is JsonArray -> AiValue.Array(map { it.toAiValue() })
    JsonNull -> AiValue.Null
    is JsonPrimitive -> when {
        isString -> AiValue.Text(content)
        else -> booleanOrNull?.let(AiValue::Boolean) ?: AiValue.Number(content)
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal fun AiValue.toJson(): JsonElement = when (this) {
    is AiValue.Object -> JsonObject(fields.mapValues { (_, value) -> value.toJson() })
    is AiValue.Array -> JsonArray(values.map { it.toJson() })
    is AiValue.Text -> JsonPrimitive(value)
    is AiValue.Boolean -> JsonPrimitive(value)
    is AiValue.Number -> {
        require(literal.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) {
            "Invalid JSON number in response schema"
        }
        JsonUnquotedLiteral(literal)
    }
    AiValue.Null -> JsonNull
}
