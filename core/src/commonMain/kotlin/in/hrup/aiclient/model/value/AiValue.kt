package `in`.hrup.aiclient.model.value

sealed interface AiValue {
    data class Object(val fields: Map<String, AiValue>) : AiValue

    data class Array(val values: List<AiValue>) : AiValue

    data class Text(val value: String) : AiValue

    // Preserve precision until the value is encoded by an adapter.
    data class Number(val literal: String) : AiValue

    data class Boolean(val value: kotlin.Boolean) : AiValue

    data object Null : AiValue
}
