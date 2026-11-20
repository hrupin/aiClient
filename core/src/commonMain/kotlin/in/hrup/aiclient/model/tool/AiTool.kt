package `in`.hrup.aiclient.model.tool

import `in`.hrup.aiclient.model.value.AiValue

data class AiTool(
    val name: String,
    val description: String,
    val inputSchema: AiValue.Object,
) {
    init {
        require(name.isNotBlank()) { "Tool name must not be blank" }
    }
}
