package `in`.hrup.aiclient.model.generation

import `in`.hrup.aiclient.model.value.AiValue

/** Requires the provider to return a JSON object validated against [schema]. */
data class JsonResponseFormat(val name: String, val schema: AiValue.Object)

class StructuredOutputException(
    message: String,
    val violations: List<String> = emptyList(),
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
